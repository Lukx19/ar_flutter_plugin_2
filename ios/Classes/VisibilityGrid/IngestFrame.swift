import Foundation
import simd

/// One LiDAR depth map for the duration of a call: f32 metres and an
/// optional one-byte confidence plane (both row-major with their own row
/// strides). [confidenceMapping] turns a source confidence value into the
/// contract byte.
struct DepthPlanes {
  let depth: UnsafeRawPointer
  let width: Int
  let height: Int
  let depthRowBytes: Int
  let confidence: UnsafeRawPointer?
  let confidenceRowBytes: Int
  let confidenceMapping: DepthConfidenceMapping
}

/// 256-entry lookup from a source confidence value to the contract byte
/// (u8 0-255, contract 2.4 confidence format 1).
final class DepthConfidenceMapping {
  let table: [UInt8]

  init(table: [UInt8]) {
    precondition(table.count == 256)
    self.table = table
  }

  /// `ARConfidenceLevel` low, medium, high (0, 1, 2) to 0, 128, 255
  /// (section 3 mapping); any other value maps to 0.
  static let arKitLevels: DepthConfidenceMapping = {
    var t = [UInt8](repeating: 0, count: 256)
    t[1] = 128
    t[2] = 255
    return DepthConfidenceMapping(table: t)
  }()

  /// Values that already are contract bytes.
  static let identity = DepthConfidenceMapping(table: (0..<256).map { UInt8($0) })
}

/// Depth mode codes, as the Android shell reports them in the depth mode
/// changed session event: disabled, smoothed (`smoothedSceneDepth`, the
/// ARCore AUTOMATIC counterpart) and raw (`sceneDepth`).
enum IngestDepthMode {
  static let disabled = 0
  static let smoothed = 1
  static let raw = 2
}

/// What the ingest shell reads from one AR frame; the ARKit implementation
/// wraps an `ARFrame` for the duration of one observer call and never
/// retains it.
protocol IngestFrame {
  /// `ARFrame.timestamp` in nanoseconds (`CLOCK_UPTIME_RAW` base).
  var timestampNs: Int64 { get }
  /// One of [IngestTrackingState].
  var trackingState: Int { get }
  /// ARCore-compatible failure code: 0 none, 1 bad state, 2 light, 3 motion, 4 features.
  var trackingFailureReason: Int { get }
  /// `ARCamera.transform`: camera-to-world, sensor oriented, column-major.
  var cameraToWorld: simd_float4x4 { get }
  /// fx, fy, cx, cy for the camera image of [imageWidth] x [imageHeight].
  var intrinsics: simd_float4 { get }
  var imageWidth: Int { get }
  var imageHeight: Int { get }
  /// Calls [body] with the frame's depth map of [mode]; false when the frame has none.
  func withDepth(mode: Int, _ body: (DepthPlanes) -> Void) -> Bool
  /// Calls [body] with the raw feature points and identifiers; false when the frame has none.
  func withFeaturePoints(_ body: (UnsafeBufferPointer<simd_float3>, UnsafeBufferPointer<UInt64>) -> Void) -> Bool
}
