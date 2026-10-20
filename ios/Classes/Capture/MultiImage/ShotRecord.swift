import Foundation
import simd

/// The images after the first one and the shot record of a burst shot,
/// committed atomically with the first JPEG by the durable store (shot
/// root, schema 7).
struct DurableShotPayload {
  struct Image {
    var index: Int
    /// Root role name ([ShotRecord.burstRole]).
    var role: String
    var bytes: [UInt8]
  }

  var shotId: UUID
  /// Images 1... in index order (index 0 is the JPEG component).
  var images: [Image]
  /// `capture3d-shot-v2` JSON ([ShotRecord]).
  var record: [UInt8]
}

/// The shot record stored with a burst shot (`shot-record.<sha>.blob`):
/// per image its index, size, content hash, exposure time, own
/// camera-to-world, intrinsics, flags and sharpness. JSON with sorted keys.
///
/// Schema `capture3d-shot-v2` (photo store layout 4) stores each image's
/// `cameraToWorld` as `T_world_cam` (column-major, OpenCV camera axes,
/// `opencv_c2w_v1`; docs/02-architecture.md, Coordinate frames) and names it
/// in `cameraConvention`. Records of schema `capture3d-shot-v1` (photo store
/// layout 3) hold the ARKit camera transform (AR camera axes); [decode]
/// converts them and never rewrites the blob.
enum ShotRecord {
  static let schema = "capture3d-shot-v2"
  /// The layout 3 schema: ARKit camera-to-world per image.
  static let schemaArkitCameraToWorld = "capture3d-shot-v1"
  static let cameraConvention = "opencv_c2w_v1"
  /// Role of every image after the first in the shot root.
  static let burstRole = "BURST"
  static let roles: Set<String> = [burstRole]

  static func encode(_ shot: CapturedShot) -> [UInt8] {
    func matrix(_ m: simd_float4x4) -> [Double] {
      [m.columns.0, m.columns.1, m.columns.2, m.columns.3].flatMap { [Double($0.x), Double($0.y), Double($0.z), Double($0.w)] }
    }
    let images: [[String: Any]] = shot.images.map { image in
      let m = image.metadata
      var value: [String: Any] = [
        "index": image.index, "width": m.width, "height": m.height, "byteLength": image.jpeg.count,
        "sha256": DurableStoreEncoding.hex(DurableStoreEncoding.sha256(image.jpeg)),
        "exposureTimestampNs": m.exposureTimestampNs, "trackingNormal": image.trackingNormal,
        "highResolution": m.highResolution, "flags": flagNames(image.flags),
      ]
      if let pose = m.cameraToWorld { value["cameraToWorld"] = openCv(matrix(pose)) }
      if let k = m.intrinsics {
        value["intrinsics"] = ["fx": Double(k.x), "fy": Double(k.y), "cx": Double(k.z), "cy": Double(k.w)]
      }
      if let score = m.sharpness { value["sharpness"] = score }
      return value
    }
    let record: [String: Any] = [
      "schema": schema, "cameraConvention": cameraConvention,
      "shotId": shot.shotId.uuidString.lowercased(), "mode": shot.mode.storedName, "images": images,
    ]
    let data = (try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])) ?? Data()
    return [UInt8](data)
  }

  /// `T_world_arcam * diag(1, -1, -1, 1)` of a column-major camera-to-world:
  /// the camera Y and Z columns change sign (as `PoseBatchWire`).
  static func openCv(_ m: [Double]) -> [Double] {
    var out = m
    for row in 0..<4 {
      out[4 + row] = -out[4 + row]
      out[8 + row] = -out[8 + row]
    }
    return out
  }

  static func flagNames(_ flags: ShotImageFlags) -> [String] {
    var names: [String] = []
    if flags.contains(.trackingNotNormal) { names.append("trackingNotNormal") }
    if flags.contains(.videoFrameFallback) { names.append("videoFrameFallback") }
    return names
  }

  /// Capture id of image [index] (1...) of the shot whose first image has
  /// [captureId]: SHA-256 hex of `<captureId>#<index>`. The session
  /// manifest, the preview reader and the photo saved event name the image
  /// by it, so each image is its own photo.
  static func imageCaptureId(_ captureId: String, index: Int) -> String {
    DurableStoreEncoding.hex(DurableStoreEncoding.sha256("\(captureId)#\(index)"))
  }

  /// One image of a decoded record.
  struct DecodedImage: Equatable {
    var index: Int
    var width: Int
    var height: Int
    var byteLength: Int64
    var sha256: String
    var exposureTimestampNs: Int64
    var trackingNormal: Bool
    var flags: ShotImageFlags
    /// `T_world_cam` (column-major, OpenCV camera axes); nil when the frame
    /// had none.
    var cameraToWorld: [Double]?
    /// fx, fy, cx, cy at the image size; nil when unknown.
    var intrinsics: [Double]?
  }

  struct Decoded: Equatable {
    var shotId: String
    var mode: String
    var images: [DecodedImage]
  }

  /// The record written by [encode], or a `capture3d-shot-v1` record whose
  /// ARKit poses are converted to `T_world_cam`; nil for anything else
  /// (unknown schema or camera convention, missing or mistyped field,
  /// unknown flag, image indices that are not 0...n-1 in order).
  static func decode(_ bytes: [UInt8]) -> Decoded? {
    guard let root = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any],
          let storedSchema = root["schema"] as? String else { return nil }
    let arkitPoses: Bool
    switch storedSchema {
    case schema:
      guard root["cameraConvention"] as? String == cameraConvention else { return nil }
      arkitPoses = false
    case schemaArkitCameraToWorld:
      arkitPoses = true
    default:
      return nil
    }
    guard let shotId = root["shotId"] as? String,
          let uuid = UUID(uuidString: shotId), uuid.uuidString.lowercased() == shotId,
          let mode = root["mode"] as? String, ["single", "burst", "quality"].contains(mode),
          let list = root["images"] as? [Any], !list.isEmpty else { return nil }
    func integer(_ value: Any?) -> Int64? { NativeCaptureWire.integer(value) }
    func number(_ value: Any?) -> Double? {
      guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
      return n.doubleValue
    }
    var images: [DecodedImage] = []
    for (position, entry) in list.enumerated() {
      guard let image = entry as? [String: Any], let index = integer(image["index"]), index == Int64(position),
            let width = integer(image["width"]), width > 0, let height = integer(image["height"]), height > 0,
            let length = integer(image["byteLength"]), length > 0,
            let sha = image["sha256"] as? String, DurableStoreEncoding.isHash(sha),
            let exposure = integer(image["exposureTimestampNs"]), exposure >= 0,
            let tracking = image["trackingNormal"] as? NSNumber, CFGetTypeID(tracking) == CFBooleanGetTypeID(),
            let flagNames = image["flags"] as? [Any] else { return nil }
      var flags: ShotImageFlags = []
      for name in flagNames {
        switch name as? String {
        case "trackingNotNormal": flags.insert(.trackingNotNormal)
        case "videoFrameFallback": flags.insert(.videoFrameFallback)
        default: return nil
        }
      }
      var pose: [Double]?
      if let value = image["cameraToWorld"] {
        guard let values = value as? [Any], values.count == 16 else { return nil }
        let parsed = values.compactMap(number)
        guard parsed.count == 16 else { return nil }
        pose = arkitPoses ? openCv(parsed) : parsed
      }
      var intrinsics: [Double]?
      if let value = image["intrinsics"] {
        guard let map = value as? [String: Any], let fx = number(map["fx"]), let fy = number(map["fy"]),
              let cx = number(map["cx"]), let cy = number(map["cy"]), fx > 0, fy > 0 else { return nil }
        intrinsics = [fx, fy, cx, cy]
      }
      images.append(DecodedImage(
        index: Int(index), width: Int(width), height: Int(height), byteLength: length, sha256: sha,
        exposureTimestampNs: exposure, trackingNormal: tracking.boolValue, flags: flags, cameraToWorld: pose,
        intrinsics: intrinsics))
    }
    return Decoded(shotId: shotId, mode: mode, images: images)
  }
}

/// One image after the first one of a committed burst shot, as the
/// committed terminal reports it: its own capture id, size, exposure time,
/// frame pose and intrinsics, read from the verified shot record.
struct CommittedShotImage: Hashable {
  var index: Int
  /// [ShotRecord.imageCaptureId] of the shot's first capture id.
  var captureId: String
  var jpegSizeBytes: Int64
  var exposureTimestampNs: Int64
  var width: Int
  var height: Int
  var trackingNormal: Bool
  /// [ShotImageFlags] raw bits.
  var flags: Int
  /// `T_world_cam` (column-major, OpenCV camera axes, `opencv_c2w_v1`); nil
  /// when the frame had none.
  var cameraToWorld: [Double]?
  /// fx, fy, cx, cy at the image size; nil when unknown.
  var intrinsics: [Double]?

  /// Photo credit and the session's picture delivery may use this image.
  var creditable: Bool { trackingNormal && cameraToWorld != nil }
}

/// The burst shot of a committed terminal: every image after the first
/// one (the attempt's JPEG), all committed by the same shot root.
struct CommittedShot: Hashable {
  var shotId: String
  /// [StillShotMode.storedName].
  var mode: String
  /// Images of the shot including the first one.
  var imageCount: Int
  /// Exposure time of the first image (the frame clock of every image's
  /// [CommittedShotImage.exposureTimestampNs]).
  var firstExposureTimestampNs: Int64
  var images: [CommittedShotImage]
}
