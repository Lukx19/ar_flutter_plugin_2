import CoreImage
import CoreVideo
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import ar_flutter_plugin_2

/// The actual HEIC encoder, durable primary and original-copy path. This is
/// host compatibility evidence; camera delivery remains a physical-device check.
final class HeicCaptureTests: XCTestCase {
  func testEncoderProducesDecodableHeicWithSensorGeometry() throws {
    let frame = ShotFixture.pixelBuffer()
    let metadata = CaptureFixture.metadata(width: CVPixelBufferGetWidth(frame), height: CVPixelBufferGetHeight(frame))
    let bytes = try XCTUnwrap(StillImageEncoder.encode(frame, quality: 95, metadata: metadata, deviceModel: "fixture", format: "heic"))
    let source = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
    XCTAssertEqual(CGImageSourceGetType(source) as String?, UTType.heic.identifier)
    let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
    XCTAssertEqual(image.width, metadata.width)
    XCTAssertEqual(image.height, metadata.height)
    XCTAssertNotNil(LaplacianSharpness.analyze(jpeg: bytes))
  }

  func testHeicCommitRecoveryAndOriginalCopyKeepFormatAndBytes() throws {
    let appFiles = CaptureFixture.temporaryDirectory("heic-round-trip")
    defer { try? FileManager.default.removeItem(at: appFiles) }
    let pixelBuffer = ShotFixture.pixelBuffer()
    let metadata = CaptureFixture.metadata(width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer))
    let bytes = try XCTUnwrap(StillImageEncoder.encode(pixelBuffer, quality: 95, metadata: metadata, deviceModel: "fixture", format: "heic"))
    var request = CaptureFixture.request()
    request.accepted.profile.requiredComponents = [.heic]
    request.accepted.profile.profileId = "single-heic"
    XCTAssertNoThrow(try NativeCaptureWire.decodeAdmission(CaptureFixture.admissionMap(request)))
    let nativeRoot = NativeCaptureStorePaths.nativeRoot(appFiles)
    let (store, _) = try CaptureFixture.store(nativeRoot)
    _ = try store.acceptCaptureBeforeExposure(request)
    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.heic, [UInt8](bytes))])
    let terminal = try XCTUnwrap(receipt.terminal)
    XCTAssertEqual(terminal.heicSizeBytes, Int64(bytes.count))
    XCTAssertNil(terminal.jpegSizeBytes)
    let event = NativeCaptureWire.event(NativeCaptureWire.terminalEvent(terminal, recoveryContext: request.recoveryContext))
    XCTAssertEqual(event["heicSizeBytes"] as? Int, bytes.count)
    XCTAssertNil(event["jpegSizeBytes"])
    let (restarted, _) = try CaptureFixture.store(nativeRoot)
    XCTAssertEqual(try restarted.queryReceipt(request.accepted.identity)?.terminal, terminal)
    let manifest = try XCTUnwrap(terminal.manifestId)
    let capture = try XCTUnwrap(terminal.captureId)
    let reference = "native-v2://\(manifest)/\(capture)"
    let owner = NativeCapturePreviewOwner(appFiles: appFiles)
    try owner.reconcile(liveReferences: [reference])
    let original = try XCTUnwrap(try owner.materialize(manifestId: manifest, captureId: capture, edge: 0))
    XCTAssertTrue(original.hasSuffix(".original.heic"))
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: original)), bytes)
    let preview = try XCTUnwrap(try owner.materialize(manifestId: manifest, captureId: capture))
    XCTAssertTrue(CapturePreviewThumbnail.valid(URL(fileURLWithPath: preview), edge: 512))
    let token = try owner.beginDeletion(references: [reference])
    XCTAssertFalse(FileManager.default.fileExists(atPath: original))
    try owner.rollbackDeletion(token: token)
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: original)), bytes)
  }

  func testStagedHeicCannotBeReadOrPersistedAsJpeg() throws {
    let config = try StillCaptureConfig.parse(["format": "heic", "resolution": ["width": 64, "height": 48]])
    let cache = StagedStillCache(config: config)
    try cache.cache(.init(imageId: "image", bytes: Data([1,2,3]), width: 64, height: 48, timestampMs: 1, format: "heic"))
    XCTAssertThrowsError(try cache.data("image", format: "jpeg"))
    let directory = CaptureFixture.temporaryDirectory("heic-staged")
    defer { try? FileManager.default.removeItem(at: directory) }
    let result = try cache.persist("image", root: directory.path, sessionFolder: "", baseName: "photo", format: "heic")
    let path = try XCTUnwrap((result["files"] as? [String: String])?["heic"])
    XCTAssertTrue(path.hasSuffix(".heic.part"))
    XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), Data([1,2,3]))
  }
}
