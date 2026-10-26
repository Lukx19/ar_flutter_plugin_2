import Foundation

/// Device-scoped calibration hints, independent of captured-session storage.
/// Namespace changes invalidate old hints without deleting settings or photos.
/// Advertised ARKit geometry never counts as an operational JPEG measurement.
final class CameraCapabilityProfileStore {
  static let namespace = "camera_capability_profile_v1."
  static let presetVersion = 13

  private let defaults: UserDefaults
  private let fingerprint: String
  private let supported: Bool
  private let advertisedStillResolutions: [[String: Int]]
  private let manualSensorControls: Bool
  private var key: String { Self.namespace + fingerprint }

  init(defaults: UserDefaults, fingerprint: String, supported: Bool,
       advertisedStillResolutions: [[String: Int]], manualSensorControls: Bool = false) {
    self.defaults = defaults
    self.fingerprint = fingerprint
    self.supported = supported
    self.advertisedStillResolutions = Self.normalized(advertisedStillResolutions)
    self.manualSensorControls = manualSensorControls
  }

  private var stored: [String: Any]? { defaults.dictionary(forKey: key) }

  var validatedResolutions: [[String: Int]] {
    Self.normalized(stored?["resolutions"] as? [[String: Int]] ?? [])
  }

  var profile: [String: Any] {
    let cache = stored
    let status = supported ? (cache?["status"] as? String ?? "pending") : "unsupported"
    return [
      "presetVersion": Self.presetVersion,
      "platformSupport": supported,
      "fingerprint": fingerprint,
      "primaryCameraId": "arkit-primary",
      // Known dimensions come from ARKit's own video/still format pairs.
      // Before iOS 26 that metadata is unavailable and geometry stays automatic.
      "supportsExactStillResolutionSelection": !advertisedStillResolutions.isEmpty,
      "advertisedJpegRegularResolutions": [[String: Int]](),
      "advertisedJpegHighResolutionResolutions": advertisedStillResolutions,
      "sharedResolutionValidationComplete": status == "supported" && !validatedResolutions.isEmpty,
      "sharedCameraProbeStatus": status,
      "sharedCameraProbeError": supported ? (cache?["error"] as? String ?? "") : "ARKit world tracking is unavailable.",
      "validatedSharedResolutions": validatedResolutions,
      "validatedRawJpegResolutions": [[String: Int]](),
      "rawJpegProbeStatus": "unsupported",
      "rawJpegProbeError": "The ARKit capture route delivers JPEG only.",
      // App-route capability, not a claim about the phone's ProRAW hardware.
      "rawCapture": false,
      "manualSensorControls": manualSensorControls,
      "flash": false,
      "primaryPhysicalCameraIds": [String](),
      "logicalMultiCamera": false,
      "concurrentCameraIdSets": [[String]](),
      "rearConcurrentCameraIds": [String](),
      "selectableRearCameras": [[String: Any]](),
      "cached": cache != nil,
    ]
  }

  /// Called only after Dart has finalized a temporary JPEG and checked its
  /// actual geometry. A legacy no-argument call cannot certify any dimensions.
  func saveSupported(arguments: Any?) {
    guard supported,
          let arguments = arguments as? [String: Any],
          let width = arguments["width"] as? Int,
          let height = arguments["height"] as? Int,
          width > 0, height > 0 else { return }
    let values = Self.normalized(validatedResolutions + [["width": width, "height": height]])
    defaults.set(["status": "supported", "resolutions": values], forKey: key)
  }

  /// Only the shared Dart stable-error classifier may call this. Transient
  /// startup, tracking, blur and per-resolution failures stay retryable there.
  func saveUnsupported(reason: String?) {
    // Successful prior output remains useful even when another attempt fails.
    guard validatedResolutions.isEmpty else { return }
    defaults.set(["status": "unsupported", "error": reason ?? "JPEG capture is unavailable."], forKey: key)
  }

  func reset() { defaults.removeObject(forKey: key) }

  private static func normalized(_ values: [[String: Int]]) -> [[String: Int]] {
    var seen = Set<String>()
    return values.filter { value in
      guard let width = value["width"], let height = value["height"], width > 0, height > 0 else { return false }
      return seen.insert("\(width)x\(height)").inserted
    }.sorted {
      let left = Int64($0["width"]!) * Int64($0["height"]!)
      let right = Int64($1["width"]!) * Int64($1["height"]!)
      return left == right ? $0["width"]! > $1["width"]! : left > right
    }
  }
}
