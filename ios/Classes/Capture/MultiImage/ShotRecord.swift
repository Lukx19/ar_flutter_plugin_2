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
  /// `capture3d-shot-v1` JSON ([ShotRecord]).
  var record: [UInt8]
}

/// The shot record stored with a burst shot (`shot-record.<sha>.blob`):
/// per image its index, size, content hash, exposure time, own
/// camera-to-world (ARKit, column-major), intrinsics, flags and sharpness.
/// JSON with sorted keys.
enum ShotRecord {
  static let schema = "capture3d-shot-v1"
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
      if let pose = m.cameraToWorld { value["cameraToWorld"] = matrix(pose) }
      if let k = m.intrinsics {
        value["intrinsics"] = ["fx": Double(k.x), "fy": Double(k.y), "cx": Double(k.z), "cy": Double(k.w)]
      }
      if let score = m.sharpness { value["sharpness"] = score }
      return value
    }
    let record: [String: Any] = [
      "schema": schema, "shotId": shot.shotId.uuidString.lowercased(), "mode": shot.mode.storedName, "images": images,
    ]
    let data = (try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys])) ?? Data()
    return [UInt8](data)
  }

  static func flagNames(_ flags: ShotImageFlags) -> [String] {
    var names: [String] = []
    if flags.contains(.trackingNotNormal) { names.append("trackingNotNormal") }
    if flags.contains(.videoFrameFallback) { names.append("videoFrameFallback") }
    return names
  }
}
