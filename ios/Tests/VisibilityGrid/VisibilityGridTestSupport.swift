import Foundation
import simd
import XCTest

@testable import ar_flutter_plugin_2

/// Shared golden bytes of `test_assets/visibility_grid/`: from the test
/// bundle when they were added as resources, else from
/// `VISIBILITY_GRID_GOLDENS`, else from the source tree next to this file.
enum VisibilityGridGoldens {
  static func data(_ name: String, file: StaticString = #filePath) throws -> Data {
    for bundle in Bundle.allBundles + [Bundle.main] {
      if let url = bundle.url(forResource: name, withExtension: nil) ?? bundle.url(
        forResource: name, withExtension: nil, subdirectory: "visibility_grid") {
        return try Data(contentsOf: url)
      }
    }
    if let dir = ProcessInfo.processInfo.environment["VISIBILITY_GRID_GOLDENS"] {
      return try Data(contentsOf: URL(fileURLWithPath: dir).appendingPathComponent(name))
    }
    let pluginRoot = URL(fileURLWithPath: "\(file)")
      .deletingLastPathComponent()  // VisibilityGrid
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // ios
      .deletingLastPathComponent()  // plugin root
    return try Data(contentsOf: pluginRoot.appendingPathComponent("test_assets/visibility_grid/\(name)"))
  }
}

final class FakeIngestClock: IngestClock {
  var now: Int64
  init(_ now: Int64 = 0) { self.now = now }
  func nowNs() -> Int64 { now }
}

/// Captures channel replies.
final class ReplyCapture {
  private(set) var replies: [Data] = []
  var last: Data? { replies.last }
  var count: Int { replies.count }
  lazy var reply: IngestReply = { [unowned self] data in self.replies.append(data) }
}

/// 8 B pull request `C3VP`, version 1, accepted kind mask.
func pullRequest(mask: Int = 0x17e) -> Data {
  var d = Data(count: 8)
  d.withUnsafeMutableBytes { raw in
    raw.storeBytes(of: IngestMessageLayout.pullRequestMagic.littleEndian, toByteOffset: 0, as: UInt32.self)
    raw.storeBytes(of: UInt16(1).littleEndian, toByteOffset: 4, as: UInt16.self)
    raw.storeBytes(of: UInt16(mask).littleEndian, toByteOffset: 6, as: UInt16.self)
  }
  return d
}

extension Data {
  func u8(_ o: Int) -> Int { Int(self[startIndex + o]) }
  func u16(_ o: Int) -> Int { withUnsafeBytes { Int(UInt16(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: UInt16.self))) } }
  func u32(_ o: Int) -> UInt32 { withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: UInt32.self)) } }
  func i32(_ o: Int) -> Int32 { withUnsafeBytes { Int32(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: Int32.self)) } }
  func i64(_ o: Int) -> Int64 { withUnsafeBytes { Int64(littleEndian: $0.loadUnaligned(fromByteOffset: o, as: Int64.self)) } }
  func f32(_ o: Int) -> Float { Float(bitPattern: u32(o)) }
  var ingestKind: Int { u8(IngestMessageLayout.kind) }
}

/// Copies [data] into a message buffer (offer input).
func messageBuffer(_ data: Data) -> IngestMessageBuffer {
  let b = IngestMessageBuffer(capacity: Swift.max(data.count, IngestMessageLayout.headerBytes))
  data.withUnsafeBytes { b.copy(from: $0.baseAddress!, length: data.count) }
  return b
}

/// The golden fixture's header values (shared by the depth, points, photo
/// saved and session event goldens): sequence 7 with two drops, sample 1 s,
/// frame 1.17 s, acquire 2 s, send 2.0005 s, tracking, interpolated, epoch 3,
/// gap -1250 us, translation (0.5, 1.25, -2).
enum GoldenFixture {
  static let sampleNs: Int64 = 1_000_000_000
  static let frameNs: Int64 = 1_170_000_000
  static let acquireNs: Int64 = 2_000_000_000
  static let sendNs: Int64 = 2_000_500_000
  static let pose: simd_float4x4 = {
    var m = matrix_identity_float4x4
    m.columns.3 = simd_float4(0.5, 1.25, -2, 1)
    return m
  }()
  static var context: IngestSampleContext {
    IngestSampleContext(
      sampleNs: sampleNs, frameNs: frameNs, acquireNs: acquireNs, trackingState: IngestTrackingState.tracking,
      groupEpoch: 3, cameraToWorld: pose, poseMatch: IngestMessageLayout.poseMatchInterpolated, poseGapUs: -1250)
  }

  /// Advances the per-kind sequence of [pull] to 7 and counts two drops, as in the goldens.
  static func primeSequence(_ pull: IngestPullHandler, kind: Int, golden: Data, clock: FakeIngestClock) {
    let message = messageBuffer(golden)
    let sink = ReplyCapture()
    for _ in 0..<7 {
      XCTAssertTrue(pull.offer(message, length: golden.count))
      pull.onPull(pullRequest(), reply: sink.reply)
    }
    pull.countSourceDrop(kind: kind)
    pull.countSourceDrop(kind: kind)
  }
}

/// Scripted frame for shell and producer tests.
final class FakeIngestFrame: IngestFrame {
  var timestampNs: Int64 = 0
  var trackingState = IngestTrackingState.tracking
  var trackingFailureReason = 0
  var cameraToWorld = matrix_identity_float4x4
  var intrinsics = simd_float4(1440, 1440, 960, 720)
  var imageWidth = 1920
  var imageHeight = 1440
  var depthWidth = 0
  var depthHeight = 0
  /// Metres, row-major.
  var depthMetres: [Float] = []
  /// Raw source confidence values (ARKit levels unless the mapping says otherwise).
  var confidence: [UInt8]?
  var confidenceMapping = DepthConfidenceMapping.arKitLevels
  var points: [simd_float3]?
  var identifiers: [UInt64] = []

  func withDepth(mode: Int, _ body: (DepthPlanes) -> Void) -> Bool {
    guard mode != IngestDepthMode.disabled, depthWidth > 0 else { return false }
    depthMetres.withUnsafeBytes { d in
      if let c = confidence {
        c.withUnsafeBytes { cb in
          body(DepthPlanes(depth: d.baseAddress!, width: depthWidth, height: depthHeight, depthRowBytes: depthWidth * 4,
                           confidence: cb.baseAddress!, confidenceRowBytes: depthWidth, confidenceMapping: confidenceMapping))
        }
      } else {
        body(DepthPlanes(depth: d.baseAddress!, width: depthWidth, height: depthHeight, depthRowBytes: depthWidth * 4,
                         confidence: nil, confidenceRowBytes: 0, confidenceMapping: confidenceMapping))
      }
    }
    return true
  }

  func withFeaturePoints(_ body: (UnsafeBufferPointer<simd_float3>, UnsafeBufferPointer<UInt64>) -> Void) -> Bool {
    guard let p = points else { return false }
    p.withUnsafeBufferPointer { pb in identifiers.withUnsafeBufferPointer { body(pb, $0) } }
    return true
  }

  /// A uniform 256x192 LiDAR map at [metres] with high confidence.
  func lidarDepth(metres: Float = 1.5) {
    depthWidth = 256
    depthHeight = 192
    depthMetres = [Float](repeating: metres, count: 256 * 192)
    confidence = [UInt8](repeating: 2, count: 256 * 192)
  }
}
