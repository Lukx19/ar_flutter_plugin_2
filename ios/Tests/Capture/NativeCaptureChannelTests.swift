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
    // Closing the view returns the shared camera to auto with the torch off.
    channel.captureViewWillDispose()
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
  func send(onChannel channel: String, message: Data?) {}
  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {}
  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection { 0 }
  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
