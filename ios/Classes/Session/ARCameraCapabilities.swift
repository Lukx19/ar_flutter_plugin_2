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

/// The ARKit capability query Dart sends (`ARKitCapabilityQuery`): one
/// channel, method and reply key set, served by the plugin's
/// `ar_flutter_plugin_2/camera_capabilities` handler. Android has no handler
/// for [method] and replies not-implemented.
enum ARKitCapabilityChannel {
  static let name = "ar_flutter_plugin_2/camera_capabilities"
  static let method = "getARKitCapabilities"

  /// Reply keys; Dart parses the same names (`ARKitCapabilityReplyKey`).
  enum Key {
    static let platform = "platform"
    static let modelIdentifier = "modelIdentifier"
    static let worldTracking = "worldTracking"
    static let sceneDepth = "sceneDepth"
    static let smoothedSceneDepth = "smoothedSceneDepth"
    static let sceneReconstruction = "sceneReconstruction"
    static let lidarDepth = "lidarDepth"
    static let highResolutionFrameCapture = "highResolutionFrameCapture"
    static let worldMap = "worldMap"
    static let videoFormats = "videoFormats"
    static let highResolutionFrameCaptureFormat = "highResolutionFrameCaptureFormat"
    /// True when the reply describes the recorded device of a playback.
    static let recordedPlayback = "recordedPlayback"
  }
}

/// Creation parameter keys of the capture view (`capture3d/capture_ar_view`);
/// Dart writes the same names (`ARCaptureViewConfig.toCreationParams`).
enum ARCaptureViewParameter {
  static let sceneDepth = "sceneDepth"
  static let smoothedSceneDepth = "smoothedSceneDepth"
  static let depthWidth = "depthWidth"
  static let depthHeight = "depthHeight"
  static let highResolutionFrameCapture = "highResolutionFrameCapture"
}

/// Size of the depth map sent to the visibility grid ingest.
struct ARDepthOutputSize: Equatable {
  var width: Int
  var height: Int

  /// LiDAR 256x192 reduced 2x2 (decision 5.2); Dart's default too.
  static let standard = ARDepthOutputSize(width: 128, height: 96)
}

/// What ARKit supports on this device. The Dart device preset table is
/// confirmed against these values at run time (no camera capability probe on
/// iOS).
struct ARCameraCapabilities: Equatable {
  /// Hardware model identifier, for example `iPhone13,3`.
  var modelIdentifier: String
  var worldTrackingSupported: Bool
  var sceneDepthSupported: Bool
  var smoothedSceneDepthSupported: Bool
  var sceneReconstructionSupported: Bool
  var videoFormats: [ARVideoFormatDescription]
  /// `recommendedVideoFormatForHighResolutionFrameCapturing`, when any.
  var highResolutionFrameCaptureFormat: ARVideoFormatDescription?
  /// The values describe the device of a replayed recording, not this one.
  var recordedPlayback = false

  /// LiDAR-class depth: `sceneDepth` frame semantics (sets source flag bit 2).
  var hasLiDARDepth: Bool { sceneDepthSupported }
  var supportsHighResolutionFrameCapture: Bool { highResolutionFrameCaptureFormat != nil }
  /// `ARSession.getCurrentWorldMap` needs world tracking.
  var supportsWorldMap: Bool { worldTrackingSupported }

  static func current() -> ARCameraCapabilities {
    ARCameraCapabilities(
      modelIdentifier: hardwareModelIdentifier(),
      worldTrackingSupported: ARWorldTrackingConfiguration.isSupported,
      sceneDepthSupported: ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth),
      smoothedSceneDepthSupported: ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth),
      sceneReconstructionSupported: ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh),
      videoFormats: ARWorldTrackingConfiguration.supportedVideoFormats.map(ARVideoFormatDescription.init),
      highResolutionFrameCaptureFormat: ARWorldTrackingConfiguration
        .recommendedVideoFormatForHighResolutionFrameCapturing.map(ARVideoFormatDescription.init))
  }

  /// Reply of [ARKitCapabilityChannel.method].
  var channelMap: [String: Any] {
    typealias Key = ARKitCapabilityChannel.Key
    var map: [String: Any] = [
      Key.platform: "ios",
      Key.modelIdentifier: modelIdentifier,
      Key.worldTracking: worldTrackingSupported,
      Key.sceneDepth: sceneDepthSupported,
      Key.smoothedSceneDepth: smoothedSceneDepthSupported,
      Key.sceneReconstruction: sceneReconstructionSupported,
      Key.lidarDepth: hasLiDARDepth,
      Key.highResolutionFrameCapture: supportsHighResolutionFrameCapture,
      Key.worldMap: supportsWorldMap,
      Key.videoFormats: videoFormats.map { $0.channelMap },
    ]
    if let format = highResolutionFrameCaptureFormat {
      map[Key.highResolutionFrameCaptureFormat] = format.channelMap
    }
    if recordedPlayback { map[Key.recordedPlayback] = true }
    return map
  }

  /// The plugin's one model identifier source: `utsname.machine`, such as
  /// `iPhone13,3` (simulators report their host architecture there, so
  /// `SIMULATOR_MODEL_IDENTIFIER` is preferred on a simulator).
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

/// Session options from the capture view creation parameters
/// ([ARCaptureViewParameter]). Absent or malformed values keep the defaults,
/// which match Dart's `ARCaptureViewConfig` defaults.
struct ARSessionOptions: Equatable {
  /// Request LiDAR `sceneDepth` when supported. Disabled for points-only runs
  /// on a LiDAR phone (plan 5.2).
  var sceneDepth = true
  var smoothedSceneDepth = false
  /// Use the video format recommended for high-resolution frame capture.
  var highResolutionFrameCapture = false
  /// Depth map size sent to the grid when [sceneDepth] is on.
  var depthOutputSize = ARDepthOutputSize.standard

  init() {}

  init(creationParams: Any?) {
    self.init()
    guard let map = creationParams as? [String: Any] else { return }
    if let value = map[ARCaptureViewParameter.sceneDepth] as? Bool { sceneDepth = value }
    if let value = map[ARCaptureViewParameter.smoothedSceneDepth] as? Bool { smoothedSceneDepth = value }
    if let value = map[ARCaptureViewParameter.highResolutionFrameCapture] as? Bool {
      highResolutionFrameCapture = value
    }
    if let width = map[ARCaptureViewParameter.depthWidth] as? Int,
       let height = map[ARCaptureViewParameter.depthHeight] as? Int,
       width > 0, height > 0 {
      depthOutputSize = ARDepthOutputSize(width: width, height: height)
    }
  }
}

/// The world tracking configuration decided from options and capabilities.
struct ARSessionConfigurationPlan: Equatable {
  var sceneDepth: Bool
  var smoothedSceneDepth: Bool
  var videoFormat: ARVideoFormatDescription?
  var depthOutputSize: ARDepthOutputSize

  var deliversSceneDepth: Bool { sceneDepth }

  init(options: ARSessionOptions, capabilities: ARCameraCapabilities) {
    sceneDepth = options.sceneDepth && capabilities.sceneDepthSupported
    smoothedSceneDepth = options.smoothedSceneDepth && capabilities.smoothedSceneDepthSupported
    videoFormat = options.highResolutionFrameCapture ? capabilities.highResolutionFrameCaptureFormat : nil
    depthOutputSize = options.depthOutputSize
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
