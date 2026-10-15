@_implementationOnly import CMarkerDetector
import Foundation

/// Swift owner of one native marker detector (the C ABI of
/// `native/marker_detector/include/marker_detector.h`, compiled into the pod
/// with the AprilTag backend). Not thread-safe: one instance per queue.
/// Results point into detector-owned memory and stay valid until the next
/// call of the same kind.
final class NativeMarkerDetector {
  static let maxMarkers = Int(C3MD_MAX_MARKERS)
  static let recordBytes = Int(C3_MARKER_DETECTION_RECORD_BYTES)
  static let tiePointBytes = Int(C3MD_TIE_POINT_BYTES)
  static let dictionaryArucoMip36h12 = Int32(C3MD_DICTIONARY_ARUCO_MIP_36H12)
  static let refineEdgeLines = Int32(C3MD_REFINE_EDGE_LINES)
  static let statusArgument = Int32(C3MD_ERROR_ARGUMENT)

  /// True when the linked library has a detection backend.
  static var available: Bool { c3md_detector_available() == 1 }

  static var abiVersion: Int32 { c3md_abi_version() }

  private let handle: OpaquePointer

  /// Nil for a dictionary the library does not decode.
  init?(dictionary: Int32 = NativeMarkerDetector.dictionaryArucoMip36h12) {
    guard let h = c3md_create(dictionary) else { return nil }
    handle = h
  }

  deinit { c3md_destroy(handle) }

  @discardableResult
  func setCamera(fx: Double, fy: Double, cx: Double, cy: Double, distortion: [Double]? = nil) -> Int32 {
    let status = c3md_set_camera(handle, fx, fy, cx, cy, nil)
    guard status == 0, let d = distortion, d.count == 5 else { return status }
    return c3md_set_distortion(handle, d[0], d[1], d[2], d[3], d[4])
  }

  @discardableResult
  func setMarkerSize(_ metres: Double) -> Int32 { c3md_set_marker_size(handle, metres) }

  /// Printed size of one marker id (0 clears it back to the default size).
  @discardableResult
  func setMarkerSize(_ metres: Double, forId markerId: Int) -> Int32 {
    c3md_set_marker_size_for_id(handle, Int32(markerId), metres)
  }

  @discardableResult
  func setQuality(minSidePx: Float = 16, minSharpness: Float = 0.12, maxViewingAngleDeg: Float = 75) -> Int32 {
    var params = C3MarkerQualityParams(
      min_side_px: minSidePx, min_sharpness: minSharpness, max_viewing_angle_deg: maxViewingAngleDeg)
    return c3md_set_quality(handle, &params)
  }

  /// Detects on a grey image read in place; returns the count or a negative status.
  func detect(_ grey: UnsafePointer<UInt8>, width: Int, height: Int, stride: Int) -> Int32 {
    c3md_detect(handle, grey, Int32(width), Int32(height), Int32(stride))
  }

  /// Poses, viewing angles and quality flags of the last detection.
  @discardableResult
  func estimatePose() -> Int32 { c3md_estimate_pose(handle) }

  var recordCount: Int { Int(c3md_record_count(handle)) }

  /// The 64 B contract records of the last detection.
  var records: UnsafeRawPointer { UnsafeRawPointer(c3md_records(handle)!) }

  /// Full-resolution tie points; returns the count or a negative status.
  func detectTiePoints(
    _ grey: UnsafePointer<UInt8>, width: Int, height: Int, stride: Int, searchDownscale: Int = 1,
    refinement: Int32 = NativeMarkerDetector.refineEdgeLines
  ) -> Int32 {
    c3md_detect_tiepoints(
      handle, grey, Int32(width), Int32(height), Int32(stride), Int32(searchDownscale), refinement)
  }

  /// The 64 B native tie point records of the last tie point detection.
  var tiePoints: UnsafeRawPointer { UnsafeRawPointer(c3md_tiepoints(handle)!) }

  /// Last detect, pose and refine times in milliseconds.
  var timingsMs: (detect: Double, pose: Double, refine: Double) {
    let t = c3md_timings(handle)!
    return (t[0], t[1], t[2])
  }
}
