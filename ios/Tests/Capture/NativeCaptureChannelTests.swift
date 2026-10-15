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

  private func configuration(mode: String, maxCacheSize: Int) -> [String: Any] {
    [
      "format": "jpeg", "resolution": ["width": 1920, "height": 1440], "captureIntervalMs": 0,
      "maxCacheSize": maxCacheSize, "jpegQuality": 90, "stillCaptureMode": mode,
    ]
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
