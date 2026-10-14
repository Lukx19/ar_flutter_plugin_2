import ARKit
import Foundation

/// One `ARWorldTrackingConfiguration` video format, as reported to Dart.
struct ARVideoFormatDescription: Equatable {
  var width: Int
  var height: Int
  var framesPerSecond: Int
  var captureDeviceType: String
  var isRecommendedForHighResolutionFrameCapturing: Bool
  var isVideoHDRSupported: Bool

  init(width: Int, height: Int, framesPerSecond: Int, captureDeviceType: String,
       isRecommendedForHighResolutionFrameCapturing: Bool, isVideoHDRSupported: Bool) {
    self.width = width
    self.height = height
    self.framesPerSecond = framesPerSecond
    self.captureDeviceType = captureDeviceType
    self.isRecommendedForHighResolutionFrameCapturing = isRecommendedForHighResolutionFrameCapturing
    self.isVideoHDRSupported = isVideoHDRSupported
  }

  init(_ format: ARConfiguration.VideoFormat) {
    self.init(
      width: Int(format.imageResolution.width),
      height: Int(format.imageResolution.height),
      framesPerSecond: format.framesPerSecond,
      captureDeviceType: format.captureDeviceType.rawValue,
      isRecommendedForHighResolutionFrameCapturing: format.isRecommendedForHighResolutionFrameCapturing,
      isVideoHDRSupported: format.isVideoHDRSupported)
  }

  var channelMap: [String: Any] {
    [
      "width": width,
      "height": height,
      "framesPerSecond": framesPerSecond,
      "captureDeviceType": captureDeviceType,
      "recommendedForHighResolutionFrameCapturing": isRecommendedForHighResolutionFrameCapturing,
      "videoHDRSupported": isVideoHDRSupported,
    ]
  }
}

/// What ARKit supports on this device. The Dart device preset table is
/// confirmed against these values at run time (no camera capability probe on
/// iOS).
struct ARCameraCapabilities: Equatable {
  var deviceModel: String
  var worldTrackingSupported: Bool
  var sceneDepthSupported: Bool
  var smoothedSceneDepthSupported: Bool
  var sceneReconstructionSupported: Bool
  var videoFormats: [ARVideoFormatDescription]
  /// `recommendedVideoFormatForHighResolutionFrameCapturing`, when any.
  var highResolutionFrameCaptureFormat: ARVideoFormatDescription?

  /// LiDAR-class depth: `sceneDepth` frame semantics (sets source flag bit 2).
  var hasLiDARDepth: Bool { sceneDepthSupported }
  var supportsHighResolutionFrameCapture: Bool { highResolutionFrameCaptureFormat != nil }
  /// `ARSession.getCurrentWorldMap` needs world tracking.
  var supportsWorldMap: Bool { worldTrackingSupported }

  static func current() -> ARCameraCapabilities {
    ARCameraCapabilities(
      deviceModel: hardwareModelIdentifier(),
      worldTrackingSupported: ARWorldTrackingConfiguration.isSupported,
      sceneDepthSupported: ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth),
      smoothedSceneDepthSupported: ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth),
      sceneReconstructionSupported: ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh),
      videoFormats: ARWorldTrackingConfiguration.supportedVideoFormats.map(ARVideoFormatDescription.init),
      highResolutionFrameCaptureFormat: ARWorldTrackingConfiguration
        .recommendedVideoFormatForHighResolutionFrameCapturing.map(ARVideoFormatDescription.init))
  }

  /// Reply of `getARCapabilities` on `capture3d/ar_capabilities`.
  var channelMap: [String: Any] {
    var map: [String: Any] = [
      "platform": "ios",
      "deviceModel": deviceModel,
      "worldTracking": worldTrackingSupported,
      "sceneDepth": sceneDepthSupported,
      "smoothedSceneDepth": smoothedSceneDepthSupported,
      "sceneReconstruction": sceneReconstructionSupported,
      "lidarDepth": hasLiDARDepth,
      "highResolutionFrameCapture": supportsHighResolutionFrameCapture,
      "worldMap": supportsWorldMap,
      "videoFormats": videoFormats.map { $0.channelMap },
    ]
    if let format = highResolutionFrameCaptureFormat {
      map["highResolutionFrameCaptureFormat"] = format.channelMap
    }
    return map
  }

  /// Model identifier such as `iPhone13,3` (simulators report their host
  /// architecture; `SIMULATOR_MODEL_IDENTIFIER` is preferred there).
  static func hardwareModelIdentifier() -> String {
    if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
      return simulated
    }
    var system = utsname()
    uname(&system)
    let machine = system.machine
    let capacity = MemoryLayout.size(ofValue: machine)
    return withUnsafeBytes(of: machine) { raw in
      String(decoding: raw.prefix(while: { $0 != 0 }).prefix(capacity), as: UTF8.self)
    }
  }
}

/// Session options from the platform view creation parameters.
struct ARSessionOptions: Equatable {
  /// Request LiDAR `sceneDepth` when supported. Disabled for points-only runs
  /// on a LiDAR phone (plan 5.2).
  var sceneDepth = true
  var smoothedSceneDepth = false
  /// Use the video format recommended for high-resolution frame capture.
  var highResolutionVideoFormat = true

  init() {}

  init(creationParams: Any?) {
    self.init()
    guard let map = creationParams as? [String: Any] else { return }
    if let value = map["sceneDepth"] as? Bool { sceneDepth = value }
    if let value = map["smoothedSceneDepth"] as? Bool { smoothedSceneDepth = value }
    if let value = map["highResolutionVideoFormat"] as? Bool { highResolutionVideoFormat = value }
  }
}

/// The world tracking configuration decided from options and capabilities.
struct ARSessionConfigurationPlan: Equatable {
  var sceneDepth: Bool
  var smoothedSceneDepth: Bool
  var videoFormat: ARVideoFormatDescription?

  var deliversSceneDepth: Bool { sceneDepth }

  init(options: ARSessionOptions, capabilities: ARCameraCapabilities) {
    sceneDepth = options.sceneDepth && capabilities.sceneDepthSupported
    smoothedSceneDepth = options.smoothedSceneDepth && capabilities.smoothedSceneDepthSupported
    videoFormat = options.highResolutionVideoFormat ? capabilities.highResolutionFrameCaptureFormat : nil
  }

  func makeConfiguration() -> ARWorldTrackingConfiguration {
    let configuration = ARWorldTrackingConfiguration()
    configuration.worldAlignment = .gravity
    configuration.planeDetection = []
    configuration.environmentTexturing = .none
    configuration.isLightEstimationEnabled = true
    configuration.isAutoFocusEnabled = true
    if sceneDepth { configuration.frameSemantics.insert(.sceneDepth) }
    if smoothedSceneDepth { configuration.frameSemantics.insert(.smoothedSceneDepth) }
    if videoFormat != nil,
       let format = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing {
      configuration.videoFormat = format
    }
    return configuration
  }
}
