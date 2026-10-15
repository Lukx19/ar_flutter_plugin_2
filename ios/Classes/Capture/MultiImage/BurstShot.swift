import Foundation
import simd

/// How a manual shutter shot was taken. Raw values are the contract byte of
/// the photo saved shot extension (`PhotoShotImageLayout.shotMode`).
///
/// Every mode runs inside the running AR session: ARKit owns the wide
/// camera while it runs, so other cameras (ultra-wide, telephoto) are not
/// available, and the session is never paused for a shot (pausing loses
/// tracking).
enum StillShotMode: Int {
  /// One high-resolution frame.
  case single = 0
  /// Several high-resolution frames in sequence, each with its own frame.
  case burst = 1
  /// One frame with ARKit multi-frame processing (`.balanced`), the in-AR HDR.
  case quality = 2

  var storedName: String {
    switch self {
    case .single: return "single"
    case .burst: return "burst"
    case .quality: return "quality"
    }
  }
}

/// Flags of one shot image; raw bits are the contract flag word of the
/// photo saved shot extension.
struct ShotImageFlags: OptionSet, Equatable {
  let rawValue: Int
  /// Tracking was not normal at this image's frame: kept, never credited.
  static let trackingNotNormal = ShotImageFlags(rawValue: 1)
  /// The high-resolution frame failed; the video frame was used.
  static let videoFrameFallback = ShotImageFlags(rawValue: 2)
}

/// One image of a shot with its own exposed frame: pose, intrinsics,
/// timestamp and tracking of that frame, never of another image.
struct ShotImage {
  var index: Int
  var jpeg: [UInt8]
  var metadata: StillExposureMetadata
  /// `ARCamera.TrackingState.normal` at this image's frame.
  var trackingNormal: Bool

  var flags: ShotImageFlags {
    var flags: ShotImageFlags = []
    if !trackingNormal { flags.insert(.trackingNotNormal) }
    if !metadata.highResolution { flags.insert(.videoFrameFallback) }
    return flags
  }

  /// Photo credit may use this image (normal tracking and a pose).
  var creditable: Bool { trackingNormal && metadata.cameraToWorld != nil }
}

/// The images of one manual shot, first image first. The first image is the
/// attempt's JPEG component; the others are committed with it (all or none).
struct CapturedShot {
  var shotId: UUID
  var mode: StillShotMode
  var images: [ShotImage]

  var first: ShotImage { images[0] }

  /// The durable payload of the images after the first; nil for a shot of
  /// one image (committed as a plain photo).
  var payload: DurableShotPayload? {
    guard images.count > 1 else { return nil }
    return DurableShotPayload(
      shotId: shotId,
      images: images.dropFirst().map { DurableShotPayload.Image(index: $0.index, role: ShotRecord.burstRole, bytes: $0.jpeg) },
      record: ShotRecord.encode(self))
  }
}

/// Burst settings of the `initializeCapture` configuration.
struct BurstSettings: Equatable {
  static let allowedImageCounts = 2...5
  static let allowedIntervalMs = 0...1_000

  /// Images per burst shot.
  var imageCount = 3
  /// Wait between the end of one image and the next request; a little hand
  /// motion over this interval gives extra viewpoints.
  var intervalMs = 150

  /// Bounded values of the `burstImageCount` and `burstIntervalMs` keys;
  /// nil for a value out of range.
  static func parse(_ map: [String: Any]) -> BurstSettings? {
    var settings = BurstSettings()
    if let value = map["burstImageCount"] {
      guard let count = (value as? NSNumber)?.intValue, allowedImageCounts.contains(count) else { return nil }
      settings.imageCount = count
    }
    if let value = map["burstIntervalMs"] {
      guard let interval = (value as? NSNumber)?.intValue, allowedIntervalMs.contains(interval) else { return nil }
      settings.intervalMs = interval
    }
    return settings
  }
}
