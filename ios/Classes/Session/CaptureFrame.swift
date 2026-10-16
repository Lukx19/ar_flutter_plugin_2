import ARKit
import CoreVideo
import Foundation
import simd
import UIKit

/// Which depth map of a frame a consumer asks for: ARKit `sceneDepth` (raw)
/// or `smoothedSceneDepth`.
enum CaptureDepthKind {
  case raw
  case smoothed
}

/// One depth map: f32 metres (`kCVPixelFormatType_DepthFloat32`) and an
/// optional confidence plane (`kCVPixelFormatType_OneComponent8`, ARKit
/// levels 0 low, 1 medium, 2 high).
struct CaptureDepthData {
  let depthMap: CVPixelBuffer
  let confidenceMap: CVPixelBuffer?
}

/// Raw feature points of one frame in world space with their identifiers.
struct CaptureFeaturePoints {
  let points: [simd_float3]
  let identifiers: [UInt64]
}

/// Ambient light of one frame (`ARLightEstimate`).
struct CaptureLightEstimate: Equatable {
  /// Lumens; about 1000 in a well-lit scene.
  let ambientIntensity: Double
  /// Kelvin.
  let ambientColorTemperature: Double
}

/// One camera frame as frame consumers see it, independent of where it came
/// from: a live `ARFrame` ([ARKitCaptureFrame]) or a recorded session
/// (`RecordedCaptureFrame`).
///
/// Consumers read a frame for the duration of one observer call or one draw
/// and never keep it: a live frame pins ARKit camera buffers, and ARKit stops
/// delivering frames when too many are retained.
protocol CaptureFrame {
  /// Sensor timestamp in seconds on the `CLOCK_UPTIME_RAW` base
  /// (`ARFrame.timestamp`; a recorded frame is rebased to the playback clock).
  var timestamp: TimeInterval { get }
  /// Camera-to-world in the landscape-right sensor orientation, +Y up,
  /// -Z forward (`ARCamera.transform`).
  var cameraTransform: simd_float4x4 { get }
  /// Pinhole intrinsics for an image of [imageResolution], column-major
  /// (`ARCamera.intrinsics`).
  var intrinsics: simd_float3x3 { get }
  var imageResolution: CGSize { get }
  /// Tracking state with its reason (`ARCamera.trackingState`).
  var trackingState: ARCamera.TrackingState { get }
  /// Camera image, bi-planar full-range YCbCr
  /// (`kCVPixelFormatType_420YpCbCr8BiPlanarFullRange`).
  var capturedImage: CVPixelBuffer { get }
  /// Maps normalized image coordinates to normalized view coordinates for an
  /// interface orientation and viewport, aspect fill (`ARFrame.displayTransform`).
  func displayTransform(for orientation: UIInterfaceOrientation, viewportSize: CGSize) -> CGAffineTransform
  /// World to camera for the interface orientation (`ARCamera.viewMatrix`).
  func viewMatrix(for orientation: UIInterfaceOrientation) -> simd_float4x4
  /// Projection for the interface orientation and viewport
  /// (`ARCamera.projectionMatrix`).
  func projectionMatrix(
    for orientation: UIInterfaceOrientation, viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
  ) -> simd_float4x4
  /// The frame's depth map of [kind]; the other kind when only that one
  /// exists; nil without depth.
  func depthData(_ kind: CaptureDepthKind) -> CaptureDepthData?
  var featurePoints: CaptureFeaturePoints? { get }
  var lightEstimate: CaptureLightEstimate? { get }
  /// Anchors of the frame's session, as `ARFrame.anchors`.
  var anchors: [ARAnchor] { get }
  /// How much of the scene the session has mapped (`ARFrame.worldMappingStatus`).
  var worldMappingStatus: ARFrame.WorldMappingStatus { get }
}

/// What one run of a frame source delivers (the part of an
/// `ARConfiguration` that frame consumers read).
struct CaptureRunConfiguration: Equatable {
  enum Depth: Equatable {
    case none
    case raw
    case smoothed
  }

  let depth: Depth
  /// The run started from a saved world map (`initialWorldMap`) and
  /// relocalizes into that map's world frame.
  let hasInitialWorldMap: Bool
  /// Path of the saved world map the run started from, as the world map
  /// channel's load named it; nil without a map or for a map staged
  /// without a path.
  let initialWorldMapSource: String?

  init(depth: Depth, hasInitialWorldMap: Bool = false, initialWorldMapSource: String? = nil) {
    self.depth = depth
    self.hasInitialWorldMap = hasInitialWorldMap
    self.initialWorldMapSource = hasInitialWorldMap ? initialWorldMapSource : nil
  }

  /// Raw `sceneDepth` is preferred over `smoothedSceneDepth`.
  init(_ configuration: ARConfiguration, initialWorldMapSource: String? = nil) {
    let semantics = configuration.frameSemantics
    if semantics.contains(.sceneDepth) {
      depth = .raw
    } else if semantics.contains(.smoothedSceneDepth) {
      depth = .smoothed
    } else {
      depth = .none
    }
    hasInitialWorldMap = (configuration as? ARWorldTrackingConfiguration)?.initialWorldMap != nil
    self.initialWorldMapSource = hasInitialWorldMap ? initialWorldMapSource : nil
  }
}

/// Where anchors are added and removed: the live `ARSession`, or the static
/// anchor store of a recorded session.
protocol CaptureAnchorHost: AnyObject {
  func add(anchor: ARAnchor)
  func remove(anchor: ARAnchor)
}

extension ARSession: CaptureAnchorHost {}

/// [CaptureFrame] over one live `ARFrame`; never stored by consumers.
struct ARKitCaptureFrame: CaptureFrame {
  let frame: ARFrame

  init(_ frame: ARFrame) { self.frame = frame }

  var timestamp: TimeInterval { frame.timestamp }
  var cameraTransform: simd_float4x4 { frame.camera.transform }
  var intrinsics: simd_float3x3 { frame.camera.intrinsics }
  var imageResolution: CGSize { frame.camera.imageResolution }
  var trackingState: ARCamera.TrackingState { frame.camera.trackingState }
  var capturedImage: CVPixelBuffer { frame.capturedImage }

  func displayTransform(for orientation: UIInterfaceOrientation, viewportSize: CGSize) -> CGAffineTransform {
    frame.displayTransform(for: orientation, viewportSize: viewportSize)
  }

  func viewMatrix(for orientation: UIInterfaceOrientation) -> simd_float4x4 {
    frame.camera.viewMatrix(for: orientation)
  }

  func projectionMatrix(
    for orientation: UIInterfaceOrientation, viewportSize: CGSize, zNear: CGFloat, zFar: CGFloat
  ) -> simd_float4x4 {
    frame.camera.projectionMatrix(for: orientation, viewportSize: viewportSize, zNear: zNear, zFar: zFar)
  }

  func depthData(_ kind: CaptureDepthKind) -> CaptureDepthData? {
    let data: ARDepthData?
    switch kind {
    case .raw: data = frame.sceneDepth ?? frame.smoothedSceneDepth
    case .smoothed: data = frame.smoothedSceneDepth ?? frame.sceneDepth
    }
    return data.map { CaptureDepthData(depthMap: $0.depthMap, confidenceMap: $0.confidenceMap) }
  }

  var featurePoints: CaptureFeaturePoints? {
    frame.rawFeaturePoints.map { CaptureFeaturePoints(points: $0.points, identifiers: $0.identifiers) }
  }

  var lightEstimate: CaptureLightEstimate? {
    frame.lightEstimate.map {
      CaptureLightEstimate(
        ambientIntensity: Double($0.ambientIntensity), ambientColorTemperature: Double($0.ambientColorTemperature))
    }
  }

  var anchors: [ARAnchor] { frame.anchors }
  var worldMappingStatus: ARFrame.WorldMappingStatus { frame.worldMappingStatus }
}
