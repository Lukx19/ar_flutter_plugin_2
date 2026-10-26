import ARKit
import CoreVideo
import Flutter
import XCTest
@testable import ar_flutter_plugin_2

/// The legacy staged still route of `arcapture_<id>` across a repeated
/// `initializeCapture`: staged stills survive a new configuration.
final class NativeCaptureChannelTests: XCTestCase {
  private var appFiles: URL!

  override func setUp() {
    super.setUp()
    appFiles = FileManager.default.temporaryDirectory
      .appendingPathComponent("native-capture-channel-\(UUID().uuidString)", isDirectory: true)
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: appFiles)
    super.tearDown()
  }

  func testRepeatedInitializeKeepsStagedStillsPersistableAndDiscardable() throws {
    let source = StillFrameSource()
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose(x: 1))
    source.frames = [frame]
    source.currentFrame = frame
    let files = appFiles!
    let opened = expectation(description: "durable root opened")
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 9, channel: FlutterMethodChannel(name: "arcapture_9", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: {
        let resources = try NativeCaptureBinding.productionResources(appFiles: files)
        opened.fulfill()
        return resources
      })
    defer { channel.captureViewWillDispose() }

    let first = try XCTUnwrap(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 4))
      as? [String: Any])
    XCTAssertEqual(first["stillCaptureMode"] as? String, "single")
    wait(for: [opened], timeout: 10)
    let persisted = try stage(channel)
    let discarded = try stage(channel)

    let second = call(channel, "initializeCapture", configuration(mode: "burst", maxCacheSize: 2))
    XCTAssertFalse(second is FlutterError, "re-initialize must succeed: \(String(describing: second))")

    let bytes = try XCTUnwrap(call(channel, "getImageData", ["imageId": persisted]) as? FlutterStandardTypedData)
    XCTAssertFalse(bytes.data.isEmpty)
    let root = appFiles.appendingPathComponent("staged", isDirectory: true).path
    let written = try XCTUnwrap(call(channel, "persistCapture", [
      "imageId": persisted, "destination": ["root": root], "sessionFolder": "session", "baseName": "photo_0001",
    ]) as? [String: Any])
    let path = try XCTUnwrap((written["files"] as? [String: Any])?["jpeg"] as? String)
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), bytes.data)
    XCTAssertEqual(call(channel, "discardCapture", ["imageId": discarded]) as? Bool, true)

    let capacity = try XCTUnwrap(call(channel, "getCaptureCapacity", nil) as? [String: Any])
    XCTAssertEqual(capacity["usedEntries"] as? Int, 0, "no staged still is orphaned")
    XCTAssertEqual(capacity["maxEntries"] as? Int, 2, "the new configuration applies afterwards")
  }

  /// The Android runtime camera controls answer on the channel through
  /// the AR session's configurable camera after `initializeCapture`; the
  /// frame source keeps running (a control never pauses AR); a recorded
  /// playback has no camera to control; closing the view resets the camera.
  func testCameraControlsRunOnTheConfigurableCameraWithoutPausingAR() throws {
    let source = StillFrameSource()
    let device = FakeCameraControlDevice()
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 10, channel: FlutterMethodChannel(name: "arcapture_10", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) },
      controlDevice: { device })
    defer { channel.captureViewWillDispose() }

    let early = call(channel, "setISO", ["isoValue": 200]) as? FlutterError
    XCTAssertEqual(early?.code, "CAPTURE_NOT_INITIALIZED")
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    let iso = try XCTUnwrap(call(channel, "setISO", ["isoValue": 200]) as? [String: Any])
    XCTAssertEqual(iso["actualISO"] as? Int, 200)
    XCTAssertEqual(device.iso, 200)
    let focus = try XCTUnwrap(call(channel, "getCurrentFocusState", nil) as? [String: Any])
    XCTAssertTrue(focus["focusRegion"] is NSNull, "absent values are sent as null")
    XCTAssertEqual(call(channel, "setTorchEnabled", ["enabled": true]) as? Bool, true)
    let unsupported = call(channel, "setFlashMode", ["mode": "on"]) as? FlutterError
    XCTAssertEqual(unsupported?.code, "CONTROL_UNSUPPORTED")
    XCTAssertEqual(source.lifecycleState, .running, "controls never pause the AR session")
    // Closing resets on the camera worker after outstanding camera work.
    channel.captureViewWillDispose()
    let reset = expectation(for: NSPredicate { _, _ in !device.isTorchOn && device.exposureMode == .auto }, evaluatedWith: nil)
    wait(for: [reset], timeout: 5)
    XCTAssertFalse(device.isTorchOn)
    XCTAssertEqual(device.exposureMode, .auto)
    XCTAssertTrue(device.isWhiteBalanceAuto)
    XCTAssertEqual(device.violations, [])

    let playback = StillFrameSource()
    playback.replaysRecordedStills = true
    let replay = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 11, channel: FlutterMethodChannel(name: "arcapture_11", binaryMessenger: SilentMessenger()),
        frameSource: playback),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files.appendingPathComponent("replay")) },
      controlDevice: { device })
    defer { replay.captureViewWillDispose() }
    XCTAssertFalse(call(replay, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    XCTAssertEqual((call(replay, "getSupportedISORange", nil) as? FlutterError)?.code, "CONTROL_UNSUPPORTED")
  }

  /// A funded shutter owns the camera until its asynchronous frame returns.
  /// ISO/brightness edits arriving meanwhile must preserve that still and the
  /// next admission instead of configuring the same device concurrently.
  func testCameraControlsWaitForOutstandingDurableStillThenNextCaptureCommits() throws {
    let source = StillFrameSource()
    let device = FakeCameraControlDevice()
    let files = appFiles!
    let messenger = SilentMessenger()
    let exposed = expectation(description: "physical still requested")
    var deliverFrame: ((CaptureFrame?, Error?) -> Void)?
    source.interceptStill = { callback in
      deliverFrame = callback
      exposed.fulfill()
    }
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 20, channel: FlutterMethodChannel(name: "arcapture_20", binaryMessenger: messenger),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) },
      controlDevice: { device })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    let first = CaptureFixture.request()
    XCTAssertFalse(call(channel, "admitNativeCaptureV2", CaptureFixture.admissionMap(first)) is FlutterError)
    wait(for: [exposed], timeout: 5)

    let isoReply = expectation(description: "ISO edit replied")
    var controlReplyCount = 0
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "setISO", arguments: ["isoValue": 200])) { value in
      XCTAssertFalse(value is FlutterError)
      controlReplyCount += 1
      isoReply.fulfill()
    })
    let mainTurn = expectation(description: "control delivery turn")
    DispatchQueue.main.async { mainTurn.fulfill() }
    wait(for: [mainTurn], timeout: 5)
    XCTAssertEqual(device.iso, 100, "ISO cannot mutate while ARKit owns an outstanding still")
    XCTAssertEqual(controlReplyCount, 0, "control result follows the actual camera change")

    let firstCommitted = expectation(description: "first durable photo committed")
    let committed = expectation(description: "both durable photos committed")
    committed.expectedFulfillmentCount = 2
    var committedCount = 0
    messenger.onEvent = { event in
      if event["kind"] as? String == "committed" {
        committedCount += 1
        if committedCount == 1 { firstCommitted.fulfill() }
        committed.fulfill()
      }
    }
    source.interceptStill = nil
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    source.frames = [frame]
    try XCTUnwrap(deliverFrame)(frame, nil)
    wait(for: [isoReply, firstCommitted], timeout: 5)
    XCTAssertEqual(device.iso, 200)
    let second = CaptureFixture.request(attemptId: "6f1c2d3e-0000-4000-8000-000000000002", commitId: "commit-2", ordinal: 2)
    XCTAssertFalse(call(channel, "admitNativeCaptureV2", CaptureFixture.admissionMap(second)) is FlutterError)
    wait(for: [committed], timeout: 10)
    XCTAssertEqual(controlReplyCount, 1)
  }

  func testStoppedCameraSettlesPendingStillOnceAndIgnoresLateFrame() throws {
    let source = StillFrameSource()
    let files = appFiles!
    let device = FakeCameraControlDevice()
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 21, channel: FlutterMethodChannel(name: "arcapture_21", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) }, controlDevice: { device })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    let exposed = expectation(description: "frame outstanding")
    var lateFrame: ((CaptureFrame?, Error?) -> Void)?
    source.interceptStill = { callback in lateFrame = callback; exposed.fulfill() }
    source.currentFrame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    let replied = expectation(description: "stopped camera resolves shutter")
    var replies = 0
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "captureHighResImage", arguments: nil)) { value in
      replies += 1
      XCTAssertTrue(value is FlutterError)
      replied.fulfill()
    })
    wait(for: [exposed], timeout: 5)
    let controlRefused = expectation(description: "queued control fails when camera stops")
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "setISO", arguments: ["isoValue": 200])) { value in
      XCTAssertEqual((value as? FlutterError)?.code, CameraControlError.updateFailedCode)
      controlRefused.fulfill()
    })
    source.stopCamera()
    wait(for: [replied, controlRefused], timeout: 1)
    XCTAssertEqual(device.iso, 100, "an unapplied control must not report success")
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    try XCTUnwrap(lateFrame)(frame, nil)
    let drained = expectation(description: "late callback drained")
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { drained.fulfill() }
    wait(for: [drained], timeout: 5)
    XCTAssertEqual(replies, 1, "late frame cannot finish the old shutter again")
    let capacity = try XCTUnwrap(call(channel, "getCaptureCapacity", nil) as? [String: Any])
    XCTAssertEqual(capacity["usedEntries"] as? Int, 0, "late frame cannot stage an image after stop")
    source.interceptStill = nil
    source.frames = [frame]
    source.resumeCamera()
    _ = try stage(channel)
    let resumed = try XCTUnwrap(call(channel, "getCaptureCapacity", nil) as? [String: Any])
    XCTAssertEqual(resumed["usedEntries"] as? Int, 1, "actual source resume allows the next capture")
    XCTAssertEqual(replies, 1)
  }

  func testDisposeResolvesPendingStillAndQueuedControlWithoutLatePublication() throws {
    let source = StillFrameSource()
    let device = FakeCameraControlDevice()
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 22, channel: FlutterMethodChannel(name: "arcapture_22", binaryMessenger: SilentMessenger()), frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) }, controlDevice: { device })
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    source.currentFrame = frame
    let requested = expectation(description: "physical request outstanding")
    var lateFrame: ((CaptureFrame?, Error?) -> Void)?
    source.interceptStill = { callback in lateFrame = callback; requested.fulfill() }
    let stopped = expectation(description: "disposed capture resolves")
    var replies = 0
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "captureHighResImage", arguments: nil)) { value in
      replies += 1
      XCTAssertTrue(value is FlutterError)
      stopped.fulfill()
    })
    wait(for: [requested], timeout: 2)
    let refused = expectation(description: "disposed queued control resolves")
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "setISO", arguments: ["isoValue": 200])) { value in
      XCTAssertEqual((value as? FlutterError)?.code, CameraControlError.updateFailedCode)
      refused.fulfill()
    })
    channel.captureViewWillDispose()
    wait(for: [stopped, refused], timeout: 2)
    source.stopCamera()
    try XCTUnwrap(lateFrame)(frame, nil)
    let lateDrained = expectation(description: "late disposed frame ignored")
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { lateDrained.fulfill() }
    wait(for: [lateDrained], timeout: 2)
    XCTAssertEqual(replies, 1)
    XCTAssertEqual(device.iso, 100)
  }

  func testInitiallyPausedCameraRejectsCaptureAndControlUntilActualResume() throws {
    let source = StillFrameSource()
    source.lifecycleState = .paused
    source.currentFrame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    let device = FakeCameraControlDevice()
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 23, channel: FlutterMethodChannel(name: "arcapture_23", binaryMessenger: SilentMessenger()), frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) }, controlDevice: { device })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    XCTAssertTrue(call(channel, "captureHighResImage", ["requiresPose": true]) is FlutterError)
    XCTAssertEqual((call(channel, "setISO", ["isoValue": 200]) as? FlutterError)?.code, CameraControlError.updateFailedCode)
    XCTAssertEqual(device.iso, 100)
    XCTAssertEqual(source.qualities.count, 0, "a source paused before observer registration cannot own a physical still")
    source.frames = [ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())]
    source.resumeCamera()
    _ = try stage(channel)
    XCTAssertEqual(source.qualities.count, 1, "only actual source resume authorizes capture")
  }

  func testLateDisposedOwnerResetCannotOverwriteReplacementCameraControls() throws {
    let device = FakeCameraControlDevice()
    let oldSource = StillFrameSource()
    let files = appFiles!
    let old = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 24, channel: FlutterMethodChannel(name: "arcapture_24", binaryMessenger: SilentMessenger()), frameSource: oldSource),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) }, controlDevice: { device })
    defer { old.captureViewWillDispose() }
    XCTAssertFalse(call(old, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    XCTAssertFalse(call(old, "setISO", ["isoValue": 400]) is FlutterError)
    let requested = expectation(description: "old physical frame pending")
    var lateFrame: ((CaptureFrame?, Error?) -> Void)?
    oldSource.interceptStill = { callback in lateFrame = callback; requested.fulfill() }
    XCTAssertFalse(call(old, "admitNativeCaptureV2", CaptureFixture.admissionMap(CaptureFixture.request())) is FlutterError)
    wait(for: [requested], timeout: 2)

    // Dart awaits capture dispose before disposing the AR view. Capture-only
    // disposal must acknowledge without pausing AR or waiting for its frame.
    XCTAssertNil(call(old, "dispose", nil))
    XCTAssertEqual(oldSource.lifecycleState, .running)
    // The view has paused the driver, but its delegate-queue stop notification
    // can reach the old camera gate after the replacement applies controls.
    oldSource.lifecycleState = .paused
    let replacementSource = StillFrameSource()
    let replacement = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 25, channel: FlutterMethodChannel(name: "arcapture_25", binaryMessenger: SilentMessenger()), frameSource: replacementSource),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files.appendingPathComponent("replacement")) },
      controlDevice: { device })
    defer { replacement.captureViewWillDispose() }
    XCTAssertFalse(call(replacement, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    XCTAssertFalse(call(replacement, "setISO", ["isoValue": 600]) is FlutterError)
    XCTAssertEqual(call(replacement, "setTorchEnabled", ["enabled": true]) as? Bool, true)
    XCTAssertEqual(device.exposureMode, .custom)

    let oldCleanup = expectation(for: NSPredicate { _, _ in oldSource.observerCount == 0 }, evaluatedWith: nil)
    oldSource.stopCamera()
    wait(for: [oldCleanup], timeout: 2)
    XCTAssertEqual(device.iso, 600)
    XCTAssertEqual(device.exposureMode, .custom, "late old reset must not return replacement exposure to Auto")
    XCTAssertTrue(device.isTorchOn, "late old reset must not switch off replacement torch")
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    try XCTUnwrap(lateFrame)(frame, nil)
  }

  func testMissingFrameWatchdogPausesActualSourceAndWaitsForStopBeforeRecovery() throws {
    let source = StillFrameSource()
    source.deliverStopOnPause = false
    let previous = ARFrameSourceRegistry.shared.current
    ARFrameSourceRegistry.shared.publish(source)
    defer { ARFrameSourceRegistry.shared.publish(previous) }
    let device = FakeCameraControlDevice()
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 26, channel: FlutterMethodChannel(name: "arcapture_26", binaryMessenger: SilentMessenger()), frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) }, controlDevice: { device })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    source.currentFrame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    let requested = expectation(description: "physical frame callback missing")
    var lateFrame: ((CaptureFrame?, Error?) -> Void)?
    source.interceptStill = { callback in lateFrame = callback; requested.fulfill() }
    let captureEnded = expectation(description: "capture fails after actual stop")
    var captureReplies = 0
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "captureHighResImage", arguments: nil)) { result in
      captureReplies += 1
      XCTAssertTrue(result is FlutterError)
      captureEnded.fulfill()
    })
    wait(for: [requested], timeout: 2)
    let controlEnded = expectation(description: "queued control truthfully fails")
    var controlReplies = 0
    XCTAssertTrue(channel.handleCaptureCall(FlutterMethodCall(methodName: "setISO", arguments: ["isoValue": 200])) { result in
      controlReplies += 1
      XCTAssertEqual((result as? FlutterError)?.code, CameraControlError.updateFailedCode)
      controlEnded.fulfill()
    })
    let paused = expectation(description: "watchdog requests actual source pause")
    source.onPause = { XCTAssertTrue(Thread.isMainThread); paused.fulfill() }
    wait(for: [paused], timeout: 12)
    XCTAssertEqual(source.pauseCalls, 1)
    XCTAssertEqual(captureReplies, 0, "time expiry alone cannot release physical camera ownership")
    XCTAssertEqual(controlReplies, 0, "controls remain blocked until actual stop acknowledgement")
    let resumed = expectation(description: "same source resumes after observed stop")
    source.onResume = { XCTAssertTrue(Thread.isMainThread); resumed.fulfill() }
    source.stopCamera()
    wait(for: [captureEnded, controlEnded, resumed], timeout: 2)
    XCTAssertEqual(source.resumeCalls, 1)
    if source.resumeCalls == 0 { source.resumeCamera() } // bounded baseline cleanup
    source.interceptStill = nil
    let frame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    source.currentFrame = frame
    source.frames = [frame]
    _ = try stage(channel)
    try XCTUnwrap(lateFrame)(frame, nil)
    let state = call(channel, "getCaptureCapacity", nil) as? [String: Any]
    XCTAssertEqual(state?["usedEntries"] as? Int, 1, "late pre-recovery frame cannot publish another image")
    XCTAssertEqual(captureReplies, 1)
  }

  private func configuration(mode: String, maxCacheSize: Int) -> [String: Any] {
    [
      "format": "jpeg", "resolution": ["width": 1920, "height": 1440], "captureIntervalMs": 0,
      "maxCacheSize": maxCacheSize, "jpegQuality": 90, "stillCaptureMode": mode,
    ]
  }

  func testTemporaryEncodingVerifiesRealBytesAndRemovesOnlyOwnedTemporaryFile() throws {
    let directory = appFiles!.appendingPathComponent("encoding-only", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let existing = directory.appendingPathComponent("existing.jpg")
    let retained = Data([1, 2, 3, 4])
    try retained.write(to: existing)
    let result = try TemporaryImageEncodingProbe().encodeAndVerify(
      ShotFixture.pixelBuffer(), format: "jpeg", quality: 95, directory: directory)
    XCTAssertEqual(result["actualFormat"] as? String, "jpeg")
    XCTAssertEqual(result["width"] as? Int, 64)
    XCTAssertEqual(result["height"] as? Int, 48)
    XCTAssertGreaterThan(result["byteLength"] as? Int ?? 0, 0)
    XCTAssertEqual(result["temporaryFileRemoved"] as? Bool, true)
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["existing.jpg"])
    XCTAssertEqual(try Data(contentsOf: existing), retained)
    XCTAssertThrowsError(try TemporaryImageEncodingProbe().encodeAndVerify(
      ShotFixture.pixelBuffer(), format: "raw", quality: 95, directory: directory))
  }

  func testTemporaryEncodingRequiresARealHighResolutionFrameAndNeverStagesFallback() throws {
    let source = StillFrameSource()
    source.currentFrame = ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())
    // No queued high-resolution frame: existing video must not pass the experiment.
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 12, channel: FlutterMethodChannel(name: "arcapture_12", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "single", maxCacheSize: 2)) is FlutterError)
    let failure = call(channel, "testTemporaryImageEncoding", ["format": "heif", "quality": 95]) as? FlutterError
    XCTAssertEqual(failure?.code, "ENCODING_CAPTURE_FAILED")
    let capacity = try XCTUnwrap(call(channel, "getCaptureCapacity", nil) as? [String: Any])
    XCTAssertEqual(capacity["usedEntries"] as? Int, 0)
  }

  func testTemporaryEncodingRequestsFastFrameAndDoesNotPopulateStagedCache() throws {
    let source = StillFrameSource()
    source.frames = [ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())]
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 13, channel: FlutterMethodChannel(name: "arcapture_13", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "burst", maxCacheSize: 2)) is FlutterError)
    let result = try XCTUnwrap(call(channel, "testTemporaryImageEncoding", ["format": "jpeg", "quality": 95]) as? [String: Any])
    XCTAssertEqual(result["actualFormat"] as? String, "jpeg")
    XCTAssertEqual(result["temporaryFileRemoved"] as? Bool, true)
    XCTAssertEqual(result["highResolution"] as? Bool, true)
    XCTAssertGreaterThan(result["captureAndEncodeUs"] as? Int ?? 0, 0)
    XCTAssertEqual(source.qualities, [.fast])
    let capacity = try XCTUnwrap(call(channel, "getCaptureCapacity", nil) as? [String: Any])
    XCTAssertEqual(capacity["usedEntries"] as? Int, 0)
  }

  func testTemporaryManualModesMeasureTheActualBurstAndBalancedRequests() throws {
    for mode in ["burst", "inArQuality"] {
      let source = StillFrameSource()
      let count = mode == "burst" ? 3 : 1
      let now = ProcessInfo.processInfo.systemUptime
      source.frames = (0..<count).map {
        ShotFixture.frame(timestamp: now + Double($0) * 0.15, pose: ShotFixture.pose(x: Float($0)))
      }
      let files = appFiles!.appendingPathComponent(mode)
      let channel = NativeCaptureChannel(
        context: CaptureChannelContext(
          viewId: 14, channel: FlutterMethodChannel(name: "arcapture_14", binaryMessenger: SilentMessenger()),
          frameSource: source),
        openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
      defer { channel.captureViewWillDispose() }
      XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: mode, maxCacheSize: 2)) is FlutterError)
      let result = try XCTUnwrap(call(channel, "testTemporaryImageEncoding", [
        "format": "jpeg", "quality": 95, "stillCaptureMode": mode,
      ]) as? [String: Any])
      XCTAssertEqual(result["stillCaptureMode"] as? String, mode)
      XCTAssertEqual(result["imageCount"] as? Int, count)
      XCTAssertEqual(result["requestedImageCount"] as? Int, count)
      XCTAssertEqual(result["temporaryFileRemoved"] as? Bool, true)
      XCTAssertEqual((result["images"] as? [[String: Any]])?.count, count)
      XCTAssertEqual(source.qualities, Array(repeating: mode == "burst" ? .fast : .balanced, count: count))
    }
  }

  func testTemporaryBurstRejectsRepeatedFrameInsteadOfCertifyingPartialMode() throws {
    let source = StillFrameSource()
    source.frames = [ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())]
    let files = appFiles!
    let channel = NativeCaptureChannel(
      context: CaptureChannelContext(
        viewId: 15, channel: FlutterMethodChannel(name: "arcapture_15", binaryMessenger: SilentMessenger()),
        frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
    defer { channel.captureViewWillDispose() }
    XCTAssertFalse(call(channel, "initializeCapture", configuration(mode: "burst", maxCacheSize: 2)) is FlutterError)
    let failure = call(channel, "testTemporaryImageEncoding", [
      "format": "jpeg", "quality": 95, "stillCaptureMode": "burst",
    ]) as? FlutterError
    XCTAssertEqual(failure?.code, "ENCODING_INCOMPLETE_BURST")
  }

  func testTemporaryBurstRejectsDifferentImageGeometryBeforeCertification() {
    var shot = ShotFixture.burst()
    shot.images[1].metadata.width += 1
    let replied = expectation(description: "mixed geometry refused")
    TemporaryImageEncodingProbe().verifyShot(shot, mode: .burst, requestedImageCount: 3,
      quality: 95, encodeUs: 0, startedAt: DispatchTime.now().uptimeNanoseconds) { outcome in
      switch outcome {
      case .success: XCTFail("A burst with mixed image dimensions must not qualify")
      case .failure(let error): XCTAssertEqual(error.code, "ENCODING_GEOMETRY_MISMATCH")
      }
      replied.fulfill()
    }
    wait(for: [replied], timeout: 5)
  }

  func testExactProfilesRefuseVideoFallbackAndWrongHighResolutionBeforeDurableCommit() throws {
    for format in ["jpeg", "heic"] {
      for lane in [CaptureLane.manual, .automatic] {
        try assertProductionOutcome(format: format, lane: lane, exact: CGSize(width: 64, height: 48),
          scenario: "fallback", expectedReason: "high-resolution-frame-unavailable")
        try assertProductionOutcome(format: format, lane: lane, exact: CGSize(width: 128, height: 96),
          scenario: "mismatch", expectedReason: "still-resolution-mismatch")
      }
    }
  }

  func testMatchingExactJpegAndHeicProfilesCommitThroughProductionOwnerOnBothLanes() throws {
    for format in ["jpeg", "heic"] {
      for lane in [CaptureLane.manual, .automatic] {
        try assertProductionOutcome(format: format, lane: lane, exact: CGSize(width: 64, height: 48),
          scenario: "matching", expectedReason: nil)
      }
    }
  }

  func testAutomaticNativeGeometryPreservesVideoFallbackWithoutExplicitStillRequest() throws {
    for format in ["jpeg", "heic"] {
      // The configured cache resolution deliberately differs from the frame.
      // It is not an explicit sensor request and must not make Automatic strict.
      try assertProductionOutcome(format: format, lane: .automatic, exact: nil,
        scenario: "fallback", expectedReason: nil)
    }
  }

  func testExactOwnerRejectsMismatchingAndReboundInitialization() throws {
    let source = StillFrameSource()
    source.exactStillResolution = CGSize(width: 64, height: 48)
    let files = appFiles!.appendingPathComponent("reinitialize")
    let channel = NativeCaptureChannel(context: CaptureChannelContext(viewId: 40,
      channel: FlutterMethodChannel(name: "arcapture_40", binaryMessenger: SilentMessenger()), frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
    defer { channel.captureViewWillDispose() }
    var config = configuration(mode: "single", maxCacheSize: 2)
    XCTAssertEqual((call(channel, "initializeCapture", config) as? FlutterError)?.code, "CAPTURE_CONFIG_REQUIRES_NEW_OWNER")
    config["resolution"] = ["width": 64, "height": 48]
    XCTAssertFalse(call(channel, "initializeCapture", config) is FlutterError)
    config["resolution"] = ["width": 128, "height": 96]
    XCTAssertEqual((call(channel, "initializeCapture", config) as? FlutterError)?.code, "CAPTURE_CONFIG_REQUIRES_NEW_OWNER")
    // A source's selection is immutable in production; even an incorrectly
    // rebound collaborator cannot silently retarget the existing exposure.
    source.exactStillResolution = CGSize(width: 128, height: 96)
    XCTAssertEqual((call(channel, "initializeCapture", config) as? FlutterError)?.code, "CAPTURE_CONFIG_REQUIRES_NEW_OWNER")
  }

  private func assertProductionOutcome(format: String, lane: CaptureLane, exact: CGSize?, scenario: String,
                                       expectedReason: String?) throws {
    let files = appFiles!.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: files) }
    let source = StillFrameSource()
    source.exactStillResolution = exact
    source.refreshCurrentVideoFrame = true
    if scenario != "fallback" {
      source.frames = [ShotFixture.frame(timestamp: ProcessInfo.processInfo.systemUptime, pose: ShotFixture.pose())]
    }
    let messenger = SilentMessenger()
    let channel = NativeCaptureChannel(context: CaptureChannelContext(viewId: 41,
      channel: FlutterMethodChannel(name: "arcapture_41", binaryMessenger: messenger), frameSource: source),
      openResources: { try NativeCaptureBinding.productionResources(appFiles: files) })
    defer { channel.captureViewWillDispose() }
    var config = configuration(mode: "single", maxCacheSize: 2)
    config["format"] = format
    if let exact = exact { config["resolution"] = ["width": Int(exact.width), "height": Int(exact.height)] }
    XCTAssertFalse(call(channel, "initializeCapture", config) is FlutterError)
    _ = call(channel, "replayNativeCaptureRecoveryV2", nil)
    let terminalReady = expectation(description: "one durable terminal: \(format) \(lane) \(scenario)")
    var terminals: [[String: Any]] = []
    messenger.onEvent = { event in
      guard let kind = event["kind"] as? String, ["committed", "abandoned"].contains(kind) else { return }
      terminals.append(event)
      terminalReady.fulfill()
    }
    var admission = CaptureFixture.admissionMap(CaptureFixture.request(lane: lane))
    var accepted = try XCTUnwrap(admission["accepted"] as? [String: Any])
    var profile = try XCTUnwrap(accepted["profile"] as? [String: Any])
    profile["requiredComponents"] = [format]
    accepted["profile"] = profile
    admission["accepted"] = accepted
    XCTAssertFalse(call(channel, "admitNativeCaptureV2", admission) is FlutterError)
    wait(for: [terminalReady], timeout: 10)
    XCTAssertEqual(terminals.count, 1)
    let terminal = try XCTUnwrap(terminals.first)
    if let expectedReason = expectedReason {
      XCTAssertEqual(terminal["kind"] as? String, "abandoned")
      XCTAssertEqual(terminal["reason"] as? String, "camera-\(expectedReason)")
      XCTAssertTrue(terminal["captureId"] == nil || terminal["captureId"] is NSNull)
      XCTAssertTrue(terminal["jpegSizeBytes"] == nil || terminal["jpegSizeBytes"] is NSNull)
      XCTAssertTrue(terminal["heicSizeBytes"] == nil || terminal["heicSizeBytes"] is NSNull)
      let performance = try XCTUnwrap(call(channel, "getPerformanceSnapshot", nil) as? [String: Any])
      let counters = try XCTUnwrap(performance["stillCaptureMetadata"] as? [String: Any])
      XCTAssertEqual(counters["highResolutionStills"] as? Int, 0, "rejected before encoding")
      XCTAssertEqual(counters["videoFallbackStills"] as? Int, 0)
    } else {
      XCTAssertEqual(terminal["kind"] as? String, "committed")
      XCTAssertNotNil(terminal["captureId"] as? String)
      XCTAssertGreaterThan(terminal[format == "heic" ? "heicSizeBytes" : "jpegSizeBytes"] as? Int ?? 0, 0)
    }
    _ = call(channel, "getNativeCaptureHealthV2", nil)
    XCTAssertEqual(terminals.count, 1)
    var changed = config
    changed["resolution"] = ["width": 320, "height": 240]
    XCTAssertEqual((call(channel, "initializeCapture", changed) as? FlutterError)?.code, "CAPTURE_CONFIG_REQUIRES_NEW_OWNER")
  }

  /// Stages one still and returns its image id.
  private func stage(_ channel: NativeCaptureChannel) throws -> String {
    let reply = try XCTUnwrap(call(channel, "captureHighResImage", ["requiresPose": true]) as? [String: Any])
    XCTAssertEqual(reply["status"] as? String, "staged")
    return try XCTUnwrap(reply["imageId"] as? String)
  }

  private func call(_ channel: NativeCaptureChannel, _ method: String, _ arguments: Any?) -> Any? {
    let replied = expectation(description: "\(method) reply")
    var value: Any?
    let handled = channel.handleCaptureCall(FlutterMethodCall(methodName: method, arguments: arguments)) { result in
      value = result
      replied.fulfill()
    }
    XCTAssertTrue(handled, "\(method) is a capture extension method")
    wait(for: [replied], timeout: 10)
    return value
  }
}

/// Accepts every outgoing message and installs no handler.
private final class SilentMessenger: NSObject, FlutterBinaryMessenger {
  var onEvent: (([String: Any]) -> Void)?
  func send(onChannel channel: String, message: Data?) {
    send(onChannel: channel, message: message, binaryReply: nil)
  }
  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {
    guard let message = message else { return }
    let codec = FlutterStandardMethodCodec.sharedInstance()
    let call = codec.decodeMethodCall(message)
    if call.method == "onNativeCaptureV2Event", let event = call.arguments as? [String: Any] { onEvent?(event) }
    callback?(codec.encodeSuccessEnvelope(nil))
  }
  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection { 0 }
  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
