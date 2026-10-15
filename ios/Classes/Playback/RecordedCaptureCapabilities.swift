import Foundation

/// ARKit capabilities during recorded playback: the recorded iPhone's, from
/// the recording, so a simulator or another phone replaying a LiDAR
/// recording runs the capture profile of the device that recorded it.
extension ARCameraCapabilities {
  /// Capture device type of the formats a recording reports.
  static let recordedCaptureDeviceType = "recorded"

  /// The recorded device of [session]: model from `meta.json` `device`,
  /// scene depth when the recording has depth maps, high-resolution frame
  /// capture when it has stills (the format is the first still's size; the
  /// replay's shutter takes the nearest recorded still). The recorder writes
  /// raw scene depth only, and no mesh.
  static func recorded(_ session: RecordedSession) -> ARCameraCapabilities {
    let meta = session.meta
    let fps = meta.videoFramesPerSecond > 0 ? meta.videoFramesPerSecond : 30
    let hasStills = !session.stills.isEmpty
    var formats: [ARVideoFormatDescription] = []
    if let width = meta.videoWidth, let height = meta.videoHeight {
      formats.append(ARVideoFormatDescription(
        width: width, height: height, framesPerSecond: fps, captureDeviceType: recordedCaptureDeviceType,
        isRecommendedForHighResolutionFrameCapturing: hasStills, isVideoHDRSupported: false))
    }
    let still = session.stills.first.map {
      ARVideoFormatDescription(
        width: $0.width, height: $0.height, framesPerSecond: fps, captureDeviceType: recordedCaptureDeviceType,
        isRecommendedForHighResolutionFrameCapturing: true, isVideoHDRSupported: false)
    }
    return ARCameraCapabilities(
      modelIdentifier: meta.deviceModel ?? "recorded",
      worldTrackingSupported: true,
      sceneDepthSupported: session.depthMapCount > 0,
      smoothedSceneDepthSupported: false,
      sceneReconstructionSupported: false,
      videoFormats: formats,
      highResolutionFrameCaptureFormat: still,
      recordedPlayback: true)
  }

  /// What a capability query describes: the recorded device when its
  /// [arguments] carry playback creation parameters (Dart
  /// `ARPlaybackConfig.toCreationParams`) that select a recording which
  /// opens, as the capture view would replay it; this device otherwise.
  static func forQuery(
    _ arguments: Any?,
    select: (Any?) -> RecordedPlaybackSelection = { RecordedPlaybackSelector.select(creationParams: $0) },
    open: (URL) throws -> RecordedSession = { try RecordedSession(directory: $0) },
    host: () -> ARCameraCapabilities = { ARCameraCapabilities.current() }
  ) -> ARCameraCapabilities {
    if case .enabled(let config) = select(arguments) {
      do {
        return recorded(try open(config.directory))
      } catch {
        RecordedPlaybackLog.write("capabilities path=\(config.directory.path) open-failed error=\(error) (host device)")
      }
    }
    return host()
  }

  /// Capabilities of the device behind [source]: the recorded one while a
  /// recording replays, this device otherwise.
  static func of(_ source: ARFrameSource?, host: () -> ARCameraCapabilities = { ARCameraCapabilities.current() })
    -> ARCameraCapabilities {
    if let recorded = source as? RecordedFrameSource { return .recorded(recorded.session) }
    return host()
  }
}
