import CryptoKit
import Foundation
import ImageIO
import XCTest

@testable import ar_flutter_plugin_2

/// The synthetic marker scene (`benchmarks/marker_detection/synthetic_scene`
/// of the parent repository, bundled into RunnerTests as folder
/// `marker_scene`) through the Swift wrapper of the C library: per view
/// `detect` plus `estimatePose` (the live path) and `detectTiePoints` with
/// edge-line refinement (the photo path), configured from the ground truth.
/// Writes the records in the shared format `capture3d-marker-scene-records-v1`
/// as the attachment `marker_scene_records_ios.txt` and to the log, one line
/// per record line prefixed `MARKER_SCENE_RECORDS `
/// (`marker_scene_records.py extract-log` restores the file);
/// `marker_scene_records.py check` and `compare` judge them against the
/// ground truth and the Android records. Here: the decoded pixels equal the
/// generator's, no call fails and no id outside the view's markers is
/// reported.
final class MarkerSceneRecordsTests: XCTestCase {
  static let format = "capture3d-marker-scene-records-v1"
  static let logPrefix = "MARKER_SCENE_RECORDS "

  /// A scene file: from the test bundle (a device cannot read the source
  /// tree), else from the parent repository (host package runs).
  static func sceneFile(_ name: String) -> URL {
    for bundle in Bundle.allBundles + [Bundle.main] {
      if let url = bundle.url(forResource: name, withExtension: nil, subdirectory: "marker_scene") { return url }
    }
    return MarkerDetectionTests.pluginRoot
      .deletingLastPathComponent()  // plugins
      .deletingLastPathComponent()  // repository root
      .appendingPathComponent("benchmarks/marker_detection/synthetic_scene/bundle/marker_scene/\(name)")
  }

  static func grey(_ url: URL) throws -> (pixels: [UInt8], width: Int, height: Int) {
    let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil), url.lastPathComponent)
    let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
    XCTAssertEqual(image.bitsPerPixel, 8, url.lastPathComponent)
    let data = try XCTUnwrap(image.dataProvider?.data) as Data
    var pixels = [UInt8](repeating: 0, count: image.width * image.height)
    data.withUnsafeBytes { raw in
      for y in 0..<image.height {
        for x in 0..<image.width { pixels[y * image.width + x] = raw[y * image.bytesPerRow + x] }
      }
    }
    return (pixels, image.width, image.height)
  }

  static func hex(_ base: UnsafeRawPointer, _ offset: Int) -> String {
    (0..<64).map { String(format: "%02x", base.load(fromByteOffset: offset + $0, as: UInt8.self)) }.joined()
  }

  func testWritesSceneRecordsThroughTheSwiftWrapper() throws {
    let truthData = try Data(contentsOf: Self.sceneFile("ground_truth.json"))
    let truth = try XCTUnwrap(JSONSerialization.jsonObject(with: truthData) as? [String: Any])
    let config = try XCTUnwrap(truth["detector"] as? [String: Any])
    let detector = try XCTUnwrap(NativeMarkerDetector(dictionary: Int32(try XCTUnwrap(config["dictionary"] as? Int))))
    detector.setMarkerSize(try XCTUnwrap(config["defaultMarkerSizeMetres"] as? Double))
    let sizes = try XCTUnwrap(config["markerSizeByIdMetres"] as? [String: Double])
    for (id, size) in sizes.sorted(by: { Int($0.key)! < Int($1.key)! }) {
      detector.setMarkerSize(size, forId: Int(id)!)
    }
    let downscale = try XCTUnwrap(config["tiePointSearchDownscale"] as? Int)
    let refinement = Int32(try XCTUnwrap(config["tiePointRefinement"] as? Int))
    var device = "simulator"
    #if !targetEnvironment(simulator)
      var info = utsname()
      uname(&info)
      device = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
    #endif
    var lines = [Self.format, "platform ios \(device) arm64"]
    let views = try XCTUnwrap(truth["views"] as? [[String: Any]])
    for view in views {
      let file = try XCTUnwrap(view["file"] as? String)
      let (pixels, width, height) = try Self.grey(Self.sceneFile(file))
      let sha = SHA256.hash(data: Data(pixels)).map { String(format: "%02x", $0) }.joined()
      XCTAssertEqual(sha, view["greySha256"] as? String, "\(file) decoded pixels")
      let camera = try XCTUnwrap(view["camera"] as? [String: Any])
      detector.setCamera(
        fx: try XCTUnwrap(camera["fx"] as? Double), fy: try XCTUnwrap(camera["fy"] as? Double),
        cx: try XCTUnwrap(camera["cx"] as? Double), cy: try XCTUnwrap(camera["cy"] as? Double))
      let ids = Set((view["markers"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? Int })
      lines.append("view \(file) \(sha)")
      let detections = pixels.withUnsafeBufferPointer {
        detector.detect($0.baseAddress!, width: width, height: height, stride: width)
      }
      XCTAssertGreaterThanOrEqual(detections, 0, "\(file) detection status")
      if detections > 0 { detector.estimatePose() }
      lines.append("detections \(detections)")
      for k in 0..<max(0, Int(detections)) {
        let id = Int(detector.records.loadUnaligned(fromByteOffset: 64 * k, as: UInt16.self))
        XCTAssertTrue(ids.contains(id), "\(file) detection of id \(id) outside the view")
        lines.append(Self.hex(detector.records, 64 * k))
      }
      let ties = pixels.withUnsafeBufferPointer {
        detector.detectTiePoints(
          $0.baseAddress!, width: width, height: height, stride: width, searchDownscale: downscale,
          refinement: refinement)
      }
      XCTAssertGreaterThanOrEqual(ties, 0, "\(file) tie point status")
      lines.append("tiepoints \(ties)")
      for k in 0..<max(0, Int(ties)) {
        let id = Int(detector.tiePoints.loadUnaligned(fromByteOffset: 64 * k, as: UInt16.self))
        XCTAssertTrue(ids.contains(id), "\(file) tie point of id \(id) outside the view")
        lines.append(Self.hex(detector.tiePoints, 64 * k))
      }
      lines.append("end")
    }
    let text = lines.joined(separator: "\n") + "\n"
    for line in lines { print(Self.logPrefix + line) }
    let attachment = XCTAttachment(
      data: Data(text.utf8), uniformTypeIdentifier: "public.plain-text")
    attachment.name = "marker_scene_records_ios.txt"
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
