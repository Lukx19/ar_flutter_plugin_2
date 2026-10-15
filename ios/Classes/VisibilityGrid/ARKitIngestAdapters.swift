import ARKit
import Foundation
import simd

/// Tracking byte of the ingest header from an ARKit camera: normal is
/// tracking, limited is paused (poses are not trusted for fusion), not
/// available is stopped.
func ingestTrackingState(_ state: ARCamera.TrackingState) -> Int {
  switch state {
  case .normal: return IngestTrackingState.tracking
  case .limited: return IngestTrackingState.paused
  case .notAvailable: return IngestTrackingState.stopped
  }
}

/// ARCore-compatible failure code of an ARKit camera (1 bad state,
/// 3 excessive motion, 4 insufficient features); 0 while tracking normally.
func ingestTrackingFailureReason(_ state: ARCamera.TrackingState) -> Int {
  switch state {
  case .normal: return 0
  case .notAvailable: return 1
  case .limited(let reason):
    switch reason {
    case .excessiveMotion: return 3
    case .insufficientFeatures: return 4
    default: return 1
    }
  }
}

/// Depth mode of a run: raw `sceneDepth` preferred over
/// `smoothedSceneDepth`; disabled without either or without LiDAR depth.
func ingestDepthMode(_ configuration: CaptureRunConfiguration?, deliversSceneDepth: Bool) -> Int {
  guard deliversSceneDepth, let depth = configuration?.depth else { return IngestDepthMode.disabled }
  switch depth {
  case .raw: return IngestDepthMode.raw
  case .smoothed: return IngestDepthMode.smoothed
  case .none: return IngestDepthMode.disabled
  }
}

/// t xyz, q xyzw of a rigid transform.
func writeTq(_ m: simd_float4x4, _ out: UnsafeMutablePointer<Float>) {
  let q = simd_normalize(simd_quatf(m))
  out[0] = m.columns.3.x; out[1] = m.columns.3.y; out[2] = m.columns.3.z
  out[3] = q.imag.x; out[4] = q.imag.y; out[5] = q.imag.z; out[6] = q.real
}

/// One [CaptureFrame] (live or recorded) for the duration of an observer
/// call; never stored.
struct CaptureIngestFrame: IngestFrame {
  let frame: CaptureFrame

  var timestampNs: Int64 { Int64((frame.timestamp * 1e9).rounded()) }
  var trackingState: Int { ingestTrackingState(frame.trackingState) }
  var trackingFailureReason: Int { ingestTrackingFailureReason(frame.trackingState) }
  var cameraToWorld: simd_float4x4 { frame.cameraTransform }
  var intrinsics: simd_float4 {
    let k = frame.intrinsics
    return simd_float4(k[0][0], k[1][1], k[2][0], k[2][1])
  }
  var imageWidth: Int { Int(frame.imageResolution.width) }
  var imageHeight: Int { Int(frame.imageResolution.height) }

  func withDepth(mode: Int, _ body: (DepthPlanes) -> Void) -> Bool {
    let data: CaptureDepthData?
    switch mode {
    case IngestDepthMode.raw: data = frame.depthData(.raw)
    case IngestDepthMode.smoothed: data = frame.depthData(.smoothed)
    default: data = nil
    }
    guard let depth = data else { return false }
    let map = depth.depthMap
    guard CVPixelBufferGetPixelFormatType(map) == kCVPixelFormatType_DepthFloat32 else { return false }
    CVPixelBufferLockBaseAddress(map, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(map, .readOnly) }
    guard let base = CVPixelBufferGetBaseAddress(map) else { return false }
    var confidence: CVPixelBuffer? = depth.confidenceMap
    if let c = confidence, CVPixelBufferGetPixelFormatType(c) != kCVPixelFormatType_OneComponent8 { confidence = nil }
    if let c = confidence { CVPixelBufferLockBaseAddress(c, .readOnly) }
    defer { if let c = confidence { CVPixelBufferUnlockBaseAddress(c, .readOnly) } }
    let cbase = confidence.flatMap { CVPixelBufferGetBaseAddress($0) }
    body(DepthPlanes(
      depth: UnsafeRawPointer(base), width: CVPixelBufferGetWidth(map), height: CVPixelBufferGetHeight(map),
      depthRowBytes: CVPixelBufferGetBytesPerRow(map),
      confidence: cbase.map { UnsafeRawPointer($0) },
      confidenceRowBytes: confidence.map { CVPixelBufferGetBytesPerRow($0) } ?? 0,
      confidenceMapping: .arKitLevels
    ))
    return true
  }

  func withFeaturePoints(_ body: (UnsafeBufferPointer<simd_float3>, UnsafeBufferPointer<UInt64>) -> Void) -> Bool {
    guard let cloud = frame.featurePoints else { return false }
    let points = cloud.points
    let ids = cloud.identifiers
    points.withUnsafeBufferPointer { p in ids.withUnsafeBufferPointer { body(p, $0) } }
    return true
  }
}

/// [AnchorBackend] over ARKit anchors: `ARAnchor` added to and removed from
/// the frame source's [CaptureAnchorHost] (the live `ARSession` or a
/// recorded session's anchor store), keyed by its identifier (ARKit hands out a new anchor object per
/// frame). Plain anchors have no tracking state of their own, so they report
/// the camera's state of the current frame.
final class ARKitAnchorBackend: AnchorBackend {
  static let anchorName = "capture3d.visibility_grid"

  /// Camera tracking of the frame being processed ([AnchorTracking] value).
  var cameraTracking = AnchorTracking.stopped

  func create(session: AnyObject, tq: UnsafePointer<Float>) -> AnyObject? {
    guard let s = session as? CaptureAnchorHost else { return nil }
    let q = simd_quatf(ix: tq[3], iy: tq[4], iz: tq[5], r: tq[6])
    guard q.length > 0.5 else { return nil }
    var m = simd_float4x4(simd_normalize(q))
    m.columns.3 = simd_float4(tq[0], tq[1], tq[2], 1)
    let anchor = ARAnchor(name: Self.anchorName, transform: m)
    s.add(anchor: anchor)
    return anchor
  }

  func detach(_ anchor: AnyObject, session: AnyObject?) {
    guard let a = anchor as? ARAnchor, let s = session as? CaptureAnchorHost else { return }
    s.remove(anchor: a)
  }

  func key(of anchor: AnyObject) -> AnyHashable {
    (anchor as? ARAnchor).map { AnyHashable($0.identifier) } ?? AnyHashable(ObjectIdentifier(anchor))
  }

  func tracking(_ anchor: AnyObject) -> Int {
    if let t = anchor as? ARTrackable { return t.isTracked ? AnchorTracking.tracking : AnchorTracking.paused }
    return cameraTracking
  }

  func readPose(_ anchor: AnyObject, into out: UnsafeMutablePointer<Float>) {
    guard let a = anchor as? ARAnchor else { return }
    writeTq(a.transform, out)
  }
}

/// [AnchorFrame] over one [CaptureFrame]; the frame's anchors are read once per call.
final class CaptureAnchorFrame: AnchorFrame {
  private let frame: CaptureFrame
  private var anchors: [ARAnchor] = []

  init(_ frame: CaptureFrame) { self.frame = frame }

  var timestampNs: Int64 { Int64((frame.timestamp * 1e9).rounded()) }
  var cameraTracking: Int { ingestTrackingState(frame.trackingState) }

  func readCameraPose(into out: UnsafeMutablePointer<Float>) { writeTq(frame.cameraTransform, out) }

  func loadUpdatedAnchors() -> Int {
    anchors = frame.anchors
    return anchors.count
  }

  func updatedAnchor(_ index: Int) -> AnyObject { anchors[index] }
}

/// Anchor tracking byte of the camera state.
func anchorTracking(_ state: ARCamera.TrackingState) -> Int {
  switch state {
  case .normal: return AnchorTracking.tracking
  case .limited: return AnchorTracking.paused
  case .notAvailable: return AnchorTracking.stopped
  }
}
