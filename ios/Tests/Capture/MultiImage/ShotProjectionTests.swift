import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import ar_flutter_plugin_2

/// Images 1 to N of a committed burst shot reach the session: the committed
/// terminal, a receipt query and restart recovery report the same shot
/// projection (each image's capture id, size, exposure time, frame pose and
/// intrinsics from the verified shot record), the wire carries it, and the
/// preview reader resolves each image by its capture id.
final class ShotProjectionTests: XCTestCase {
  private var root: URL!

  override func setUp() {
    super.setUp()
    root = CaptureFixture.temporaryDirectory("shot-projection")
  }

  override func tearDown() {
    try? FileManager.default.removeItem(at: root)
    super.tearDown()
  }

  /// A small real JPEG, so the preview reader can decode and scale it.
  static func jpeg(_ shade: UInt8, width: Int = 96, height: Int = 64) -> [UInt8] {
    let pixels = [UInt8](repeating: shade, count: width * height)
    let provider = CGDataProvider(data: Data(pixels) as CFData)!
    let image = CGImage(
      width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider,
      decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    XCTAssertTrue(CGImageDestinationFinalize(destination))
    return [UInt8](data as Data)
  }

  private func shot() -> CapturedShot {
    let base = ShotFixture.burst()
    return CapturedShot(shotId: base.shotId, mode: .burst, images: base.images.map { image in
      var copy = image
      copy.jpeg = Self.jpeg(UInt8(40 + 60 * image.index))
      return copy
    })
  }

  private func commitShot(_ store: DurableCaptureStore, _ request: CaptureCommitRequest = CaptureFixture.request())
    throws -> (CaptureTerminal, CapturedShot) {
    let captured = shot()
    _ = try store.acceptCaptureBeforeExposure(request)
    let receipt = try store.commitStreamedFinalized(
      request.template, exposureTimestampNanoseconds: captured.first.metadata.exposureTimestampNs,
      components: [(.jpeg, captured.first.jpeg)], shot: captured.payload)
    return (try XCTUnwrap(receipt.terminal), captured)
  }

  func testTheCommittedTerminalReportsEveryLaterImageFromTheShotRecord() throws {
    let (store, _) = try CaptureFixture.store(root)
    let (terminal, captured) = try commitShot(store)
    let captureId = try XCTUnwrap(terminal.captureId)
    let reported = try XCTUnwrap(terminal.shot)
    XCTAssertEqual(reported.shotId, ShotFixture.shotId.uuidString.lowercased())
    XCTAssertEqual(reported.mode, "burst")
    XCTAssertEqual(reported.imageCount, 3)
    XCTAssertEqual(reported.firstExposureTimestampNs, captured.first.metadata.exposureTimestampNs)
    XCTAssertEqual(reported.images.map { $0.index }, [1, 2])
    for image in reported.images {
      let source = captured.images[image.index]
      XCTAssertEqual(image.captureId, ShotRecord.imageCaptureId(captureId, index: image.index))
      XCTAssertNotEqual(image.captureId, captureId)
      XCTAssertEqual(image.jpegSizeBytes, Int64(source.jpeg.count))
      XCTAssertEqual(image.exposureTimestampNs, source.metadata.exposureTimestampNs)
      XCTAssertEqual(image.width, 4032)
      XCTAssertEqual(image.height, 3024)
      XCTAssertEqual(image.trackingNormal, source.trackingNormal)
      XCTAssertEqual(image.flags, source.flags.rawValue)
      let pose = try XCTUnwrap(image.cameraToWorld)
      XCTAssertEqual(pose[12], Double(source.metadata.cameraToWorld!.columns.3.x), accuracy: 1e-6, "its own frame's pose")
      XCTAssertEqual(image.intrinsics, [2940, 2940, 2016, 1512])
    }
    XCTAssertTrue(reported.images[0].creditable)
    XCTAssertFalse(reported.images[1].creditable, "the limited-tracking image is kept, never credited")

    // The receipt query and the startup projection report the same shot.
    XCTAssertEqual(try store.queryReceipt(CaptureFixture.request().accepted.identity)?.terminal?.shot, reported)
    let (reopened, _) = try CaptureFixture.store(root)
    let projection = try XCTUnwrap(reopened.recoverAndProject().first)
    XCTAssertEqual(projection.shot, reported, "restart recovery projects images 1 to N")
    let replayed = NativeCaptureRecoveryReplay.event(projection)
    let live = NativeCaptureWire.terminalEvent(terminal, recoveryContext: CaptureFixture.context())
    XCTAssertEqual(replayed.terminalIdentity, live.terminalIdentity, "the replay equals the live terminal")
  }

  func testThePlainCommitAndAQualityShotReportNoShot() throws {
    let (store, _) = try CaptureFixture.store(root)
    let request = CaptureFixture.request()
    _ = try store.acceptCaptureBeforeExposure(request)
    let receipt = try store.commitStreamedFinalized(request.template, exposureTimestampNanoseconds: 1, components: [(.jpeg, [1, 2, 3])])
    XCTAssertNil(receipt.terminal?.shot)
    XCTAssertNil(NativeCaptureWire.event(NativeCaptureWire.terminalEvent(receipt.terminal!, recoveryContext: nil))["shot"])
  }

  func testAShotRecordThatDoesNotVerifyReportsNoLaterImage() throws {
    let (store, _) = try CaptureFixture.store(root)
    let (terminal, captured) = try commitShot(store)
    let record = try XCTUnwrap(captured.payload?.record)
    let blob = root.appendingPathComponent(
      "store/sessions/\(DurableStoreEncoding.safeName("session-ios"))/assets/\(terminal.captureId!)/"
        + DurableCaptureStore.shotRecordBlobName(hash: DurableStoreEncoding.hex(DurableStoreEncoding.sha256(record))))
    var tampered = record
    tampered[tampered.count / 2] ^= 0x01
    try Data(tampered).write(to: blob)
    let receipt = try XCTUnwrap(try store.queryReceipt(CaptureFixture.request().accepted.identity))
    XCTAssertEqual(receipt.terminal?.kind, .committedPicture, "the first image stays committed")
    XCTAssertNil(receipt.terminal?.shot, "an unverified record never reports images")
  }

  func testTheShotRecordDecodesStrictly() throws {
    let encoded = ShotRecord.encode(shot())
    let decoded = try XCTUnwrap(ShotRecord.decode(encoded))
    XCTAssertEqual(decoded.images.map { $0.index }, [0, 1, 2])
    XCTAssertEqual(decoded.images[2].flags, .trackingNotNormal)
    var json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded)) as? [String: Any])
    XCTAssertEqual(json["schema"] as? String, "capture3d-shot-v2")
    XCTAssertEqual(json["cameraConvention"] as? String, "opencv_c2w_v1")
    json["schema"] = "capture3d-shot-v3"
    XCTAssertNil(ShotRecord.decode([UInt8](try JSONSerialization.data(withJSONObject: json))), "unknown schema")
    json["schema"] = ShotRecord.schema
    json["cameraConvention"] = "arkit_c2w_v1"
    XCTAssertNil(ShotRecord.decode([UInt8](try JSONSerialization.data(withJSONObject: json))), "unknown convention")
    json["cameraConvention"] = ShotRecord.cameraConvention
    var images = try XCTUnwrap(json["images"] as? [[String: Any]])
    images.swapAt(0, 1)
    json["images"] = images
    XCTAssertNil(ShotRecord.decode([UInt8](try JSONSerialization.data(withJSONObject: json))), "indices out of order")
    XCTAssertNil(ShotRecord.decode(Array("{}".utf8)))
  }

  /// The image pose is stored as `T_world_cam`: the ARKit camera transform
  /// with its camera Y and Z columns negated.
  func testTheShotRecordStoresOpenCvCameraToWorld() throws {
    let source = shot()
    let decoded = try XCTUnwrap(ShotRecord.decode(ShotRecord.encode(source)))
    let arkit = try XCTUnwrap(source.images[1].metadata.cameraToWorld)
    let pose = try XCTUnwrap(decoded.images[1].cameraToWorld)
    let columns = [arkit.columns.0, arkit.columns.1, arkit.columns.2, arkit.columns.3]
    for (c, column) in columns.enumerated() {
      let sign: Double = c == 1 || c == 2 ? -1 : 1
      for (r, value) in [column.x, column.y, column.z, column.w].enumerated() {
        XCTAssertEqual(pose[c * 4 + r], sign * Double(value), "column \(c) row \(r)")
      }
    }
  }

  /// A layout 3 record (`capture3d-shot-v1`, ARKit camera transforms) as the
  /// previous app version wrote it: read and converted to `T_world_cam`.
  func testALayoutThreeShotRecordIsReadAndConverted() throws {
    // `test_assets/native_store` is copied into the test bundle; host runs
    // read the plugin tree.
    var url = GoldenFixture.bundle.resourceURL?.appendingPathComponent("native_store/shot_record_v1.json")
    if url == nil || !FileManager.default.fileExists(atPath: url!.path) {
      url = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("test_assets/native_store/shot_record_v1.json")
    }
    let bytes = [UInt8](try Data(contentsOf: try XCTUnwrap(url)))
    let decoded = try XCTUnwrap(ShotRecord.decode(bytes))
    XCTAssertEqual(decoded.images.map { $0.index }, [0, 1, 2])
    // Recorded ARKit pose of image 1: identity rotation at (1.01, 2, 3).
    XCTAssertEqual(decoded.images[1].cameraToWorld, [
      1, 0, 0, 0, -0.0, -1, -0.0, -0.0, -0.0, -0.0, -1, -0.0, 1.0099999904632568, 2, 3, 1,
    ])
    XCTAssertNil(decoded.images[2].cameraToWorld)
  }

  func testTheWireCarriesTheShotOfACommittedTerminal() throws {
    let (store, _) = try CaptureFixture.store(root)
    let (terminal, _) = try commitShot(store)
    let map = NativeCaptureWire.event(NativeCaptureWire.terminalEvent(terminal, recoveryContext: CaptureFixture.context()))
    let shot = try XCTUnwrap(map["shot"] as? [String: Any])
    XCTAssertEqual(Set(shot.keys), ["shotId", "mode", "imageCount", "firstExposureTimestampNs", "images"])
    XCTAssertEqual(shot["firstExposureTimestampNs"] as? Int, 9_000_000_000)
    XCTAssertEqual(shot["imageCount"] as? Int, 3)
    let images = try XCTUnwrap(shot["images"] as? [[String: Any]])
    XCTAssertEqual(images.count, 2)
    XCTAssertEqual(Set(images[0].keys), [
      "index", "captureId", "jpegSizeBytes", "exposureTimestampNs", "width", "height", "trackingNormal", "flags",
      "cameraToWorld", "cameraToWorldConvention", "intrinsics",
    ])
    XCTAssertEqual(images[0]["cameraToWorldConvention"] as? String, "opencv_c2w_v1")
    XCTAssertEqual((images[0]["cameraToWorld"] as? [Double])?.count, 16)
    XCTAssertEqual(images[1]["trackingNormal"] as? Bool, false)
    XCTAssertEqual(images[1]["flags"] as? Int, ShotImageFlags.trackingNotNormal.rawValue)
  }

  func testThePreviewReaderResolvesEveryShotImageByItsCaptureId() throws {
    let (store, _) = try CaptureFixture.store(root)
    let (terminal, _) = try commitShot(store)
    let reader = NativeCapturePreviewReader(storeRoot: root.appendingPathComponent("store"))
    let manifest = try XCTUnwrap(terminal.manifestId)
    let captureId = try XCTUnwrap(terminal.captureId)
    var previews: [Data] = []
    for id in [captureId] + (terminal.shot?.images.map { $0.captureId } ?? []) {
      let target = root.appendingPathComponent("previews/\(id).jpg")
      XCTAssertTrue(reader.materialize(manifestId: manifest, captureId: id, target: target, edge: 512, cancelled: { false }), id)
      previews.append(try Data(contentsOf: target))
    }
    XCTAssertEqual(previews.count, 3)
    XCTAssertEqual(Set(previews).count, 3, "each id resolves its own image")
    let unknown = ShotRecord.imageCaptureId(captureId, index: 3)
    XCTAssertFalse(reader.materialize(manifestId: manifest, captureId: unknown,
                                      target: root.appendingPathComponent("previews/unknown.jpg"), edge: 512, cancelled: { false }),
                   "an index the root does not hold never resolves")
  }
}
