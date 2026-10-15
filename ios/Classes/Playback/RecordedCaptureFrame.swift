import ARKit
import CoreVideo
import Foundation
import simd
import UIKit

/// Camera geometry of a recorded frame, matching what `ARFrame` and
/// `ARCamera` compute for a live frame: the image and intrinsics are in the
/// landscape-right sensor orientation; an interface orientation rotates them
/// about the camera's viewing axis, and the viewport crops them (aspect fill).
enum RecordedCameraGeometry {
  /// Quarter turns from the sensor orientation to the interface orientation:
  /// landscape right 0, portrait 1, landscape left 2, portrait upside down 3.
  static func quarterTurns(_ orientation: UIInterfaceOrientation) -> Int {
    switch orientation {
    case .landscapeRight: return 0
    case .landscapeLeft: return 2
    case .portraitUpsideDown: return 3
    default: return 1
    }
  }

  /// Normalized image coordinates (top-left origin) to normalized view
  /// coordinates for [orientation] and [viewportSize], aspect fill.
  static func displayTransform(
    imageResolution: CGSize, orientation: UIInterfaceOrientation, viewportSize: CGSize
  ) -> CGAffineTransform {
    let turns = quarterTurns(orientation)
    let rotation: CGAffineTransform
    switch turns {
    case 0: rotation = .identity
    case 1: rotation = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 1, ty: 0)
    case 2: rotation = CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 1, ty: 1)
    default: rotation = CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 1)
    }
    let (kx, ky) = aspectFillScale(imageResolution: imageResolution, turns: turns, viewportSize: viewportSize)
    let fill = CGAffineTransform(a: kx, b: 0, c: 0, d: ky, tx: 0.5 - 0.5 * kx, ty: 0.5 - 0.5 * ky)
    return rotation.concatenating(fill)
  }

  /// Camera-to-world of the orientation's camera frame (x right and y up on
  /// the screen).
  static func orientedCameraTransform(_ cameraTransform: simd_float4x4, orientation: UIInterfaceOrientation)
    -> simd_float4x4 {
    cameraTransform * screenRotation(quarterTurns(orientation))
  }

  /// World to camera for [orientation].
  static func viewMatrix(_ cameraTransform: simd_float4x4, orientation: UIInterfaceOrientation) -> simd_float4x4 {
    orientedCameraTransform(cameraTransform, orientation: orientation).inverse
  }

  /// Projection (Metal clip space, depth 0 at [zNear] to 1 at [zFar]) of
  /// the orientation's camera frame for [viewportSize], aspect fill.
  static func projectionMatrix(
    intrinsics k: simd_float3x3, imageResolution: CGSize, orientation: UIInterfaceOrientation,
    viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
  ) -> simd_float4x4 {
    let width = Float(imageResolution.width)
    let height = Float(imageResolution.height)
    let near = Float(zNear)
    let far = Float(zFar)
    guard width > 0, height > 0, far > near else { return matrix_identity_float4x4 }
    // Sensor camera (x right, y up, -z forward) to the landscape image's
    // clip space; image v grows downwards.
    let sensor = simd_float4x4(columns: (
      simd_float4(2 * k[0][0] / width, 0, 0, 0),
      simd_float4(0, 2 * k[1][1] / height, 0, 0),
      simd_float4(1 - 2 * k[2][0] / width, 2 * k[2][1] / height - 1, far / (near - far), -1),
      simd_float4(0, 0, near * far / (near - far), 0)))
    let turns = quarterTurns(orientation)
    let (kx, ky) = aspectFillScale(imageResolution: imageResolution, turns: turns, viewportSize: viewportSize)
    // Landscape clip x, y to the rotated image's clip x, y: the same quarter
    // turns as the camera frame, applied the other way round.
    let clipRotation = screenRotation(turns).inverse
    let fill = simd_float4x4(diagonal: simd_float4(Float(kx), Float(ky), 1, 1))
    return fill * clipRotation * sensor * screenRotation(turns)
  }

  /// Rotation about the viewing axis taking the orientation's camera axes to
  /// the sensor camera axes ([turns] quarter turns counter-clockwise).
  static func screenRotation(_ turns: Int) -> simd_float4x4 {
    let c: Float
    let s: Float
    switch ((turns % 4) + 4) % 4 {
    case 0: (c, s) = (1, 0)
    case 1: (c, s) = (0, 1)
    case 2: (c, s) = (-1, 0)
    default: (c, s) = (0, -1)
    }
    return simd_float4x4(columns: (
      simd_float4(c, s, 0, 0),
      simd_float4(-s, c, 0, 0),
      simd_float4(0, 0, 1, 0),
      simd_float4(0, 0, 0, 1)))
  }

  /// How much larger than the viewport the rotated, aspect-filled image is
  /// in view x and y (both >= 1, one of them exactly 1).
  static func aspectFillScale(imageResolution: CGSize, turns: Int, viewportSize: CGSize) -> (CGFloat, CGFloat) {
    let rotatedWidth = turns % 2 == 0 ? imageResolution.width : imageResolution.height
    let rotatedHeight = turns % 2 == 0 ? imageResolution.height : imageResolution.width
    guard rotatedWidth > 0, rotatedHeight > 0, viewportSize.width > 0, viewportSize.height > 0 else { return (1, 1) }
    let scale = max(viewportSize.width / rotatedWidth, viewportSize.height / rotatedHeight)
    return (rotatedWidth * scale / viewportSize.width, rotatedHeight * scale / viewportSize.height)
  }
}

/// A replayed frame: the recorded camera, its image (decoded video, the
/// previous image between video frames, or the placeholder), depth and
/// feature points, with the timestamp rebased to the playback clock.
struct RecordedCaptureFrame: CaptureFrame {
  let timestamp: TimeInterval
  /// Timestamp in the recording (`ARFrame.timestamp` when it was recorded).
  let recordedTimestamp: TimeInterval
  let cameraTransform: simd_float4x4
  let intrinsics: simd_float3x3
  let imageResolution: CGSize
  let trackingState: ARCamera.TrackingState
  let capturedImage: CVPixelBuffer
  let depth: CaptureDepthData?
  let featurePoints: CaptureFeaturePoints?
  let lightEstimate: CaptureLightEstimate?
  let anchors: [ARAnchor]
  /// The recorded `ARFrame.worldMappingStatus`; a still answers not available.
  var worldMappingStatus: ARFrame.WorldMappingStatus = .notAvailable

  func displayTransform(for orientation: UIInterfaceOrientation, viewportSize: CGSize) -> CGAffineTransform {
    RecordedCameraGeometry.displayTransform(
      imageResolution: imageResolution, orientation: orientation, viewportSize: viewportSize)
  }

  func viewMatrix(for orientation: UIInterfaceOrientation) -> simd_float4x4 {
    RecordedCameraGeometry.viewMatrix(cameraTransform, orientation: orientation)
  }

  func projectionMatrix(
    for orientation: UIInterfaceOrientation, viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
  ) -> simd_float4x4 {
    RecordedCameraGeometry.projectionMatrix(
      intrinsics: intrinsics, imageResolution: imageResolution, orientation: orientation,
      viewportSize: viewportSize, zNear: zNear, zFar: zFar)
  }

  /// A recording holds one depth kind (`sceneDepth`); it answers both.
  func depthData(_ kind: CaptureDepthKind) -> CaptureDepthData? { depth }
}

/// Turns decoded depth maps into the `CaptureDepthData` pixel buffers live
/// frames carry: f32 metres (0 where the recording has no depth) and the
/// ARKit confidence levels. Buffers come from pools, so steady replay does
/// not allocate. Used from the replay queue.
final class RecordedDepthBuffers {
  private var depthPool: CVPixelBufferPool?
  private var confidencePool: CVPixelBufferPool?
  private var poolWidth = 0
  private var poolHeight = 0

  func make(_ map: RecordedDepthMap) -> CaptureDepthData? {
    if map.width != poolWidth || map.height != poolHeight {
      depthPool = Self.pool(width: map.width, height: map.height, format: kCVPixelFormatType_DepthFloat32)
      confidencePool = Self.pool(width: map.width, height: map.height, format: kCVPixelFormatType_OneComponent8)
      poolWidth = map.width
      poolHeight = map.height
    }
    guard let depthPool = depthPool, let confidencePool = confidencePool else { return nil }
    var depthBuffer: CVPixelBuffer?
    var confidenceBuffer: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, depthPool, &depthBuffer) == kCVReturnSuccess,
          CVPixelBufferPoolCreatePixelBuffer(nil, confidencePool, &confidenceBuffer) == kCVReturnSuccess,
          let depth = depthBuffer, let confidence = confidenceBuffer else { return nil }
    CVPixelBufferLockBaseAddress(depth, [])
    CVPixelBufferLockBaseAddress(confidence, [])
    defer {
      CVPixelBufferUnlockBaseAddress(confidence, [])
      CVPixelBufferUnlockBaseAddress(depth, [])
    }
    guard let depthBase = CVPixelBufferGetBaseAddress(depth),
          let confidenceBase = CVPixelBufferGetBaseAddress(confidence) else { return nil }
    let depthRow = CVPixelBufferGetBytesPerRow(depth)
    let confidenceRow = CVPixelBufferGetBytesPerRow(confidence)
    map.millimetres.withUnsafeBufferPointer { mm in
      map.confidence.withUnsafeBufferPointer { levels in
        for y in 0..<map.height {
          let metres = depthBase.advanced(by: y * depthRow).assumingMemoryBound(to: Float32.self)
          let row = confidenceBase.advanced(by: y * confidenceRow).assumingMemoryBound(to: UInt8.self)
          for x in 0..<map.width {
            metres[x] = Float32(mm[y * map.width + x]) / 1000
            row[x] = levels[y * map.width + x]
          }
        }
      }
    }
    return CaptureDepthData(depthMap: depth, confidenceMap: confidence)
  }

  private static func pool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
    var pool: CVPixelBufferPool?
    let attributes: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: format,
      kCVPixelBufferWidthKey as String: width,
      kCVPixelBufferHeightKey as String: height,
      kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
    ]
    CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
    return pool
  }
}
