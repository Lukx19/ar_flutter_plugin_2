import Foundation

/// Catalogue codes (`session_log_codes.dart`) the iOS shell records. Codes
/// shared with Android keep the Android data keys. Foundation only, so the
/// Flutter- and ARKit-free host test packages can link the session log.
enum NativeSessionLogCodes {
  static let trackingLimited = "TRACKING_LIMITED"
  static let arSessionInterrupted = "AR_SESSION_INTERRUPTED"
  static let thermalThrottling = "THERMAL_THROTTLING"
  static let memoryPressure = "MEMORY_PRESSURE"
  static let backgroundTaskEnded = "BACKGROUND_TASK_ENDED"
  static let worldMapFailed = "WORLD_MAP_FAILED"
  static let worldMapRelocalized = "WORLD_MAP_RELOCALIZED"
  static let worldMapApplied = "WORLD_MAP_APPLIED"
  static let markerDetectionFailed = "MARKER_DETECTION_FAILED"
  static let rendererFailed = "RENDERER_FAILED"
  static let poseRefused = "POSE_REFUSED"
}
