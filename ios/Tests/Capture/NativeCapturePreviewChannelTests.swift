import Flutter
import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// The app-wide preview channel (`ar_flutter_plugin_2/native_capture_preview`)
/// on the wire, as History and the gallery reach it: a committed photo
/// resolves to a preview copy only after the app's startup reconcile, and
/// the reconcile resolves an interrupted preview deletion against the live
/// references.
final class NativeCapturePreviewChannelTests: XCTestCase {
  private var appFiles: URL!
  private var messenger: PreviewChannelMessenger!

  override func setUp() {
    super.setUp()
    // The first unanswered reply ends the test instead of chaining 60 s waits.
    continueAfterFailure = false
    appFiles = CaptureFixture.temporaryDirectory("preview-channel")
    messenger = PreviewChannelMessenger()
    _ = NativeCapturePreviewChannel.register(messenger: messenger, owner: NativeCapturePreviewOwner(appFiles: appFiles))
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: appFiles)
    super.tearDown()
  }

  /// Commits one photo to the durable store; returns its preview reference parts.
  private func commitPhoto() throws -> (manifest: String, capture: String, reference: String) {
    let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(appFiles))
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let jpeg = StillQualityTests.jpeg(width: 1200, height: 900) { x, y in UInt8((x ^ y) & 0xff) }
    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [UInt8](jpeg))])
    let manifest = try XCTUnwrap(receipt.terminal?.manifestId)
    let capture = try XCTUnwrap(receipt.terminal?.captureId)
    return (manifest, capture, "native-v2://\(manifest)/\(capture)")
  }

  private func invoke(_ method: String, _ arguments: [String: Any]) -> Any? {
    let done = expectation(description: method)
    var reply: Any?
    messenger.invoke(NativeCapturePreviewChannel.name, method, arguments) { value in
      XCTAssertTrue(Thread.isMainThread)
      reply = value
      done.fulfill()
    }
    // Replies are serialized on the channel's utility queue, which a loaded
    // host starves: a preview decode and every reply queued behind it can
    // exceed 10 s (a 10 s wait failed after 11.9 s and 44.4 s; the first
    // test took 40.4 s with the 60 s wait).
    wait(for: [done], timeout: 60)
    XCTAssertFalse(reply is FlutterError, "\(method): \(String(describing: reply))")
    return reply
  }

  private func materialize(_ photo: (manifest: String, capture: String, reference: String), detailed: Bool = false) -> String? {
    var arguments: [String: Any] = ["manifestId": photo.manifest, "captureId": photo.capture, "requestId": UUID().uuidString]
    if detailed { arguments["detailed"] = true }
    return invoke("materializeNativeCapturePreview", arguments) as? String
  }

  func testCommittedPhotoPreviewResolvesOnlyAfterTheChannelReconciles() throws {
    let photo = try commitPhoto()

    XCTAssertNil(materialize(photo), "without the startup reconcile no preview resolves")
    XCTAssertNil(invoke("reconcileNativeCapturePreviews", ["references": [photo.reference]]))

    let path = try XCTUnwrap(materialize(photo), "the reconciled store resolves the preview")
    XCTAssertTrue(CapturePreviewThumbnail.valid(URL(fileURLWithPath: path), edge: 512))
    let detail = try XCTUnwrap(materialize(photo, detailed: true))
    XCTAssertTrue(CapturePreviewThumbnail.valid(URL(fileURLWithPath: detail), edge: 1024))
  }

  func testReconcileResolvesAnInterruptedPreviewDeletionAgainstTheLiveReferences() throws {
    let photo = try commitPhoto()
    XCTAssertNil(invoke("reconcileNativeCapturePreviews", ["references": [photo.reference]]))
    XCTAssertNotNil(materialize(photo))

    // Deletion begun, then interrupted before commit or rollback.
    XCTAssertNotNil(invoke("beginNativeCapturePreviewDeletion", ["references": [photo.reference]]) as? String)
    XCTAssertNil(materialize(photo), "the pending deletion fences readers")
    XCTAssertNil(invoke("reconcileNativeCapturePreviews", ["references": [photo.reference]]))
    XCTAssertNotNil(materialize(photo), "a still live photo gets its preview back")

    XCTAssertNotNil(invoke("beginNativeCapturePreviewDeletion", ["references": [photo.reference]]) as? String)
    XCTAssertNil(invoke("reconcileNativeCapturePreviews", ["references": [String]()]))
    XCTAssertNil(materialize(photo), "a photo no longer live stays deleted")
    let leftovers = try FileManager.default.contentsOfDirectory(atPath: NativeCaptureStorePaths.previews(appFiles).path)
    XCTAssertFalse(leftovers.contains { $0.hasPrefix("deleting-") }, "the reconcile closes the transaction")
  }
}

/// Keeps the channel's handler and delivers encoded method calls to it.
private final class PreviewChannelMessenger: NSObject, FlutterBinaryMessenger {
  private var handlers: [String: FlutterBinaryMessageHandler] = [:]
  private var nextConnection: FlutterBinaryMessengerConnection = 0

  func invoke(_ channel: String, _ method: String, _ arguments: Any?, reply: @escaping (Any?) -> Void) {
    let codec = FlutterStandardMethodCodec.sharedInstance()
    guard let handler = handlers[channel] else { return XCTFail("no handler on \(channel)") }
    handler(codec.encode(FlutterMethodCall(methodName: method, arguments: arguments))) { data in
      let value = data.map { codec.decodeEnvelope($0) } ?? nil
      reply(value is NSNull ? nil : value)
    }
  }

  func send(onChannel channel: String, message: Data?) {}

  func send(onChannel channel: String, message: Data?, binaryReply callback: FlutterBinaryReply? = nil) {}

  func setMessageHandlerOnChannel(
    _ channel: String, binaryMessageHandler handler: FlutterBinaryMessageHandler? = nil
  ) -> FlutterBinaryMessengerConnection {
    handlers[channel] = handler
    nextConnection += 1
    return nextConnection
  }

  func cleanUpConnection(_ connection: FlutterBinaryMessengerConnection) {}
}
