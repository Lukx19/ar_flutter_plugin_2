import Foundation
import XCTest
@testable import ar_flutter_plugin_2

/// Canonical camera frame golden (`opencv_c2w_v1`) through the iOS pose
/// encoder `PoseBatchWire`: `test_assets/visibility_grid/frame_convention_golden.json`,
/// written by `scripts/geometry/frame_convention_golden.py` and read by the
/// Kotlin `FrameConventionGoldenTest` and the Dart
/// `frame_convention_golden_test.dart` too. Each case is an ARCore or ARKit
/// style tracking camera pose; `T_world_cam = T_world_arcam * diag(1,-1,-1,1)`.
///
/// Foundation only: runs on the macOS host (`run_frame_convention_host_tests.sh`),
/// in the session host package on a simulator and in `RunnerTests`.
final class FrameConventionGoldenTests: XCTestCase {
  struct Golden: Decodable {
    let convention: String
    let cases: [Case]
  }

  struct Case: Decodable {
    let name: String
    let platform: String
    let sequence: Int64
    let systemTimestampMs: Int64
    let timestampNs: Int64
    let tracking: Tracking
    let openCv: OpenCv
    let packedSampleHex: String
    let intrinsics: Intrinsics
    let worldUpInImage: String?
    let projections: [Projection]
  }

  struct Tracking: Decodable {
    let position: [Float]
    let rotation: [Float]
    let transformBits: [String]
  }

  struct OpenCv: Decodable {
    let convention: String
    let transformBits: [String]
    let rotationBits: [String]
  }

  struct Intrinsics: Decodable {
    let fx: Double
    let fy: Double
    let cx: Double
    let cy: Double
  }

  struct Projection: Decodable {
    let label: String
    let world: [Double]
    let camera: [Double]
    let pixel: [Double]?
  }

  static let relativePath = "test_assets/visibility_grid/frame_convention_golden.json"

  /// The plugin source tree first (host and simulator runs; this file is a
  /// symlink in the temporary packages), then the test bundle (`RunnerTests`).
  static func loadGolden() throws -> Golden {
    let pluginRoot = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
      .deletingLastPathComponent().deletingLastPathComponent()
      .deletingLastPathComponent().deletingLastPathComponent()
    var url = pluginRoot.appendingPathComponent(relativePath)
    if !FileManager.default.fileExists(atPath: url.path),
       let bundled = Bundle(for: FrameConventionGoldenTests.self).resourceURL?
         .appendingPathComponent("visibility_grid/frame_convention_golden.json") {
      url = bundled
    }
    return try JSONDecoder().decode(Golden.self, from: Data(contentsOf: url))
  }

  static func float(_ hex: String) -> Float { Float(bitPattern: UInt32(hex, radix: 16)!) }

  static func bytes(_ hex: String) -> Data {
    var data = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
      let next = hex.index(index, offsetBy: 2)
      data.append(UInt8(hex[index..<next], radix: 16)!)
      index = next
    }
    return data
  }

  static func sample(_ c: Case) -> PoseWireSample {
    PoseWireSample(
      sequence: c.sequence,
      systemTimestampMs: c.systemTimestampMs,
      timestampNs: c.timestampNs,
      confidence: 1,
      trackingState: .tracking,
      isTracking: true,
      position: c.tracking.position,
      rotation: c.tracking.rotation,
      transform: c.tracking.transformBits.map(float))
  }

  static func readFloat(_ data: Data, _ offset: Int) -> Float {
    var bits: UInt32 = 0
    for i in 0..<4 { bits |= UInt32(data[data.startIndex + offset + i]) << UInt32(i * 8) }
    return Float(bitPattern: bits)
  }

  func testGoldenCoversArCoreAndArKitStylePoses() throws {
    let golden = try Self.loadGolden()
    XCTAssertEqual(golden.convention, "opencv_c2w_v1")
    XCTAssertTrue(golden.cases.contains { $0.platform == "arcore" })
    XCTAssertTrue(golden.cases.contains { $0.platform == "arkit" })
  }

  func testEncodedSamplesEqualTheGoldenBytes() throws {
    for c in try Self.loadGolden().cases {
      XCTAssertEqual(PoseBatchWire.encode(Self.sample(c)), Self.bytes(c.packedSampleHex), c.name)
    }
  }

  func testOpenCvTransformAndQuaternionBitsEqualTheGolden() throws {
    for c in try Self.loadGolden().cases {
      let transform = PoseBatchWire.openCvCameraToWorld(c.tracking.transformBits.map(Self.float))
      XCTAssertEqual(transform.map(\.bitPattern), c.openCv.transformBits.map { UInt32($0, radix: 16)! }, c.name)
      let rotation = PoseBatchWire.quaternion(fromColumnMajor: transform)
      XCTAssertEqual(rotation.map(\.bitPattern), c.openCv.rotationBits.map { UInt32($0, radix: 16)! }, c.name)
    }
  }

  func testEncodedPosesProjectPointsAheadToPositiveDepthWithImageYDownward() throws {
    for c in try Self.loadGolden().cases {
      let encoded = PoseBatchWire.encode(Self.sample(c))
      let t = (0..<16).map { Double(Self.readFloat(encoded, 80 + $0 * 4)) }
      let k = c.intrinsics
      for row in c.projections {
        let label = "\(c.name) \(row.label)"
        let d = (0..<3).map { row.world[$0] - t[12 + $0] }
        // Camera point R^T (p - c): row i of R^T is column i of T.
        let camera = (0..<3).map { i in (0..<3).reduce(0.0) { $0 + t[i * 4 + $1] * d[$1] } }
        for i in 0..<3 { XCTAssertEqual(camera[i], row.camera[i], accuracy: 1e-9, label) }
        guard let pixel = row.pixel else {
          XCTAssertLessThan(camera[2], 0, "\(label) is behind the camera")
          continue
        }
        XCTAssertGreaterThan(camera[2], 0, "\(label) is in front of the camera")
        XCTAssertEqual(k.fx * camera[0] / camera[2] + k.cx, pixel[0], accuracy: 1e-6, label)
        XCTAssertEqual(k.fy * camera[1] / camera[2] + k.cy, pixel[1], accuracy: 1e-6, label)
      }
      let up = c.projections.first { $0.label == "ahead_2m_world_up" }!.pixel!
      switch c.worldUpInImage {
      case "v_decreases": XCTAssertLessThan(up[1], k.cy, "\(c.name): world up is image up")
      case "u_decreases": XCTAssertLessThan(up[0], k.cx, "\(c.name): portrait, world up is image left")
      default: break
      }
    }
  }
}
