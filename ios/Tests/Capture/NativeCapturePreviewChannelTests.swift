import Flutter
import Foundation
import ImageIO
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

  func testGuidanceDescriptorAcceptsOnlyBoundedNativeRoundTripMetadata() {
    let descriptor = "guidance_preview_v1:1200:900:6"
    let expected = GuidancePreviewLuma.SourceGeometry(width: 1200, height: 900, exifOrientation: 6)
    XCTAssertEqual(GuidancePreviewLuma.sourceGeometry([kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFImageDescription: descriptor]]), expected)
    XCTAssertEqual(GuidancePreviewLuma.sourceGeometry([kCGImagePropertyIPTCDictionary: [kCGImagePropertyIPTCCaptionAbstract: descriptor]]), expected)
    XCTAssertNil(GuidancePreviewLuma.sourceGeometry([:]), "legacy previews cannot invent geometry")
    XCTAssertNil(GuidancePreviewLuma.sourceGeometry([
      kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFImageDescription: descriptor],
      kCGImagePropertyIPTCDictionary: [kCGImagePropertyIPTCCaptionAbstract: "guidance_preview_v1:900:1200:1"],
    ]))
    for invalid in ["legacy:1200:900:6", "guidance_preview_v1:0:900:1", "guidance_preview_v1:1200:900:9", "guidance_preview_v1::1200:900:1", "guidance_preview_v1:65537:900:1", "guidance_preview_v1:+1200:900:1", String(repeating: "x", count: 81)] {
      XCTAssertNil(GuidancePreviewLuma.sourceGeometry([kCGImagePropertyIPTCDictionary: [kCGImagePropertyIPTCCaptionAbstract: invalid]]))
    }
  }

  func testGuidanceLumaUsesAuthorizedBoundedPreviewAndExactSourceGeometry() throws {
    let photo = try commitPhoto()
    XCTAssertNil(invoke("guidanceNativeCapturePreview", ["manifestId": photo.manifest, "captureId": photo.capture]), "unreconciled references must not expose a guidance preview")
    _ = invoke("reconcileNativeCapturePreviews", ["references": [photo.reference]])
    let previewPath = try XCTUnwrap(materialize(photo))
    let previewSource = try XCTUnwrap(CGImageSourceCreateWithURL(URL(fileURLWithPath: previewPath) as CFURL, nil))
    let previewProperties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(previewSource, 0, nil) as? [CFString: Any])
    XCTAssertEqual(GuidancePreviewLuma.sourceGeometry(previewProperties),
                   GuidancePreviewLuma.SourceGeometry(width: 1200, height: 900, exifOrientation: 1),
                   "Source geometry must round-trip through the actual native JPEG writer: \(previewProperties)")
    XCTAssertNotNil(GuidancePreviewLuma.read(previewPath, cancelled: { false }), "Authorized native preview must be directly readable before channel encoding")
    let payload = try XCTUnwrap(invoke("guidanceNativeCapturePreview", ["manifestId": photo.manifest, "captureId": photo.capture]) as? [String: Any])
    XCTAssertEqual(payload["wireVersion"] as? String, "guidance_luma_v1")
    XCTAssertEqual(payload["sourceWidth"] as? Int, 1200)
    XCTAssertEqual(payload["sourceHeight"] as? Int, 900)
    XCTAssertEqual(payload["exifOrientation"] as? Int, 1)
    let width = try XCTUnwrap(payload["width"] as? Int), height = try XCTUnwrap(payload["height"] as? Int)
    XCTAssertLessThanOrEqual(width, 256); XCTAssertLessThanOrEqual(height, 256)
    let luma = try XCTUnwrap((payload["luma"] as? FlutterStandardTypedData)?.data)
    XCTAssertEqual(luma.count, width * height)
    XCTAssertLessThan(luma[(height / 4) * width + width / 2], 24)
    XCTAssertGreaterThan(luma[(height * 3 / 4) * width + width / 2], 231)
  }

  /// Commits one photo to the durable store; returns its preview reference parts.
  private func commitPhoto() throws -> (manifest: String, capture: String, reference: String) {
    let (store, _) = try CaptureFixture.store(NativeCaptureStorePaths.nativeRoot(appFiles))
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let jpeg = StillQualityTests.jpeg(width: 1200, height: 900) { _, y in y < 450 ? 0 : 255 }
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

  func testOriginalInspectionKeepsFullResolutionAndDeletionRollback() throws {
    let photo = try commitPhoto()
    _ = invoke("reconcileNativeCapturePreviews", ["references": [photo.reference]])
    let arguments: [String: Any] = ["manifestId": photo.manifest, "captureId": photo.capture,
                                     "requestId": UUID().uuidString, "original": true]
    let path = try XCTUnwrap(invoke("materializeNativeCapturePreview", arguments) as? String)
    let bytes = try Data(contentsOf: URL(fileURLWithPath: path))
    let source = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
    let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 1200)
    XCTAssertEqual(properties[kCGImagePropertyPixelHeight] as? Int, 900)
    let token = try XCTUnwrap(invoke("beginNativeCapturePreviewDeletion", ["references": [photo.reference]]) as? String)
    XCTAssertFalse(FileManager.default.fileExists(atPath: path))
    XCTAssertNil(invoke("materializeNativeCapturePreview", arguments) as? String)
    _ = invoke("rollbackNativeCapturePreviewDeletion", ["token": token])
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), bytes)
    try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
    XCTAssertEqual(invoke("materializeNativeCapturePreview", arguments) as? String, path)
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), bytes)
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
