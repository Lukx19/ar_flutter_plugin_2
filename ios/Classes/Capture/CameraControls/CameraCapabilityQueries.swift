import ARKit
import AVFoundation
import Foundation
import ImageIO
import UIKit

/// What the Android-shaped camera capability queries of
/// `ar_flutter_plugin_2/camera_capabilities` read on iOS.
struct CameraCapabilitySnapshot: Equatable {
  /// Full-resolution still size of the high-resolution capture format.
  var stillSize: CGSize?
  /// `ARWorldTrackingConfiguration.supportedVideoFormats` sizes.
  var videoSizes: [CGSize]
  /// ISO range of the AR session's configurable camera.
  var isoRange: ClosedRange<Float>?
  /// Exposure duration range in seconds.
  var exposureRange: ClosedRange<Double>?
  /// Intrinsics map of the newest frame of a running capture view.
  var intrinsics: [String: Any]?

  static func == (lhs: CameraCapabilitySnapshot, rhs: CameraCapabilitySnapshot) -> Bool {
    lhs.stillSize == rhs.stillSize && lhs.videoSizes == rhs.videoSizes && lhs.isoRange == rhs.isoRange
      && lhs.exposureRange == rhs.exposureRange && (lhs.intrinsics == nil) == (rhs.intrinsics == nil)
  }
}

/// The Android camera capability queries (`CameraCapabilityQuerier`) with
/// the Android reply shapes, answered from ARKit and the configurable camera
/// ARKit runs. Stills are JPEG only: ARKit's high-resolution frames are
/// processed images and RAW photo output needs its own `AVCaptureSession`,
/// which would pause AR, so `raw+jpeg` is never supported.
enum CameraCapabilityQueries {
  /// Android `generateISORange`: the common ISO values inside the range.
  static let commonISOValues = [50, 100, 200, 400, 800, 1600, 3200, 6400, 12800]

  static func handles(_ method: String) -> Bool {
    ["getSupportedResolutions", "getSupportedFormats", "getSupportedISORange", "getSupportedExposureRange",
     "isResolutionSupported", "isFormatSupported", "getCameraIntrinsics"].contains(method)
  }

  /// Reply of [method], or a `(code, message)` error.
  static func answer(_ method: String, arguments: Any?, snapshot: CameraCapabilitySnapshot) -> Result<Any, CameraControlError> {
    switch method {
    case "getSupportedResolutions": return .success(resolutions(snapshot).map { ["width": $0.0, "height": $0.1] })
    case "getSupportedFormats": return .success(["jpeg"])
    case "getSupportedISORange":
      guard let range = snapshot.isoRange else { return .success([Int]()) }
      return .success(commonISOValues.filter { Float($0) >= range.lowerBound && Float($0) <= range.upperBound })
    case "getSupportedExposureRange":
      guard let range = snapshot.exposureRange,
            let micros = CameraControlBridge.microsRange(range.lowerBound, range.upperBound) else {
        return .success([String: Int64]())
      }
      return .success(["min": micros.lowerBound, "max": micros.upperBound])
    case "isResolutionSupported":
      guard let map = arguments as? [String: Any], let width = map["width"] as? Int, let height = map["height"] as? Int else {
        return .failure(CameraControlError(code: "INVALID_ARGUMENTS", message: "Width and height are required"))
      }
      return .success(resolutions(snapshot).contains { $0.0 == width && $0.1 == height })
    case "isFormatSupported":
      guard let format = arguments as? String else {
        return .failure(CameraControlError(code: "INVALID_ARGUMENTS", message: "Format string is required"))
      }
      return .success(format == "jpeg")
    case "getCameraIntrinsics":
      guard let intrinsics = snapshot.intrinsics else {
        return .failure(CameraControlError(code: "INTRINSICS_ERROR", message: "Unable to extract camera intrinsics: no capture view is running"))
      }
      return .success(intrinsics)
    default:
      return .failure(CameraControlError.unsupported("\(method) is not a camera capability query"))
    }
  }

  /// Still size first, then the video formats, largest first, unique.
  static func resolutions(_ snapshot: CameraCapabilitySnapshot) -> [(Int, Int)] {
    var out: [(Int, Int)] = []
    for size in ([snapshot.stillSize].compactMap { $0 } + snapshot.videoSizes.sorted { $0.width * $0.height > $1.width * $1.height }) {
      let value = (Int(size.width), Int(size.height))
      if value.0 > 0, value.1 > 0, !out.contains(where: { $0 == value }) { out.append(value) }
    }
    return out
  }

  /// Metadata of existing still mechanisms, never proof of merged HDR or
  /// Smart HDR. The iOS 26 balanced request may silently fall back to a plain
  /// high-resolution frame; no returned mode/effect oracle currently exists.
  static func hdrSupport(supportsBalancedAPI: Bool, highResolutionFrameCapture: Bool,
                         videoHDR: Bool) -> [String: Any] {
    let balanced = supportsBalancedAPI && highResolutionFrameCapture
    return [
      "video": videoHDR, "photo": NSNull(),
      "pipelinePhoto": NSNull(), "mergedPhoto": NSNull(),
      "exposureBracketing": false,
      "balancedPhotoProcessing": balanced,
      "balancedPhotoMayFallback": balanced,
    ]
  }

  /// Read-only metadata. An unconfigured AVCapturePhotoOutput cannot prove
  /// RAW/ProRAW codec support, and opening a second session would compete with
  /// ARKit. Those flags remain unknown rather than reporting a false refusal.
  static func capabilityReport() -> [String: Any] {
    let snapshot = current()
    let capabilities = ARCameraCapabilities.current()
    let device = ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera
    var hardwareSizes = Set<String>()
    var hardwareDimensions = [[String: Int]]()
    if #available(iOS 16.0, *) {
      for format in device?.formats ?? [] {
        for dimensions in format.supportedMaxPhotoDimensions where dimensions.width > 0 && dimensions.height > 0 {
          let key = "\(dimensions.width)x\(dimensions.height)"
          if hardwareSizes.insert(key).inserted {
            hardwareDimensions.append(["width": Int(dimensions.width), "height": Int(dimensions.height)])
          }
        }
      }
    }
    hardwareDimensions.sort { $0["width"]! * $0["height"]! > $1["width"]! * $1["height"]! }
    let stillDimensions = snapshot.stillSize.map { [["width": Int($0.width), "height": Int($0.height)]] } ?? []
    let encoders = CGImageDestinationCopyTypeIdentifiers() as? [String] ?? []
    let encoderFormats = ["jpeg"] + (encoders.contains("public.heic") ? ["heif"] : [])
    func value(_ flag: Bool?) -> Any { flag.map { $0 as Any } ?? NSNull() }
    let supportsBalancedAPI: Bool
    if #available(iOS 26.0, *) { supportsBalancedAPI = true } else { supportsBalancedAPI = false }
    return [
      "schemaVersion": 1, "evidence": "advertised",
      "deviceModel": capabilities.modelIdentifier, "platform": "ios",
      "osVersion": UIDevice.current.systemVersion, "cameraId": "arkit-primary",
      "hardwareFormats": ["jpeg": true, "heif": NSNull(), "raw": NSNull(),
                          "raw+jpeg": NSNull(), "raw+heif": NSNull()] as [String: Any],
      "pipelineFormats": ["jpeg"], "encoderFormats": encoderFormats,
      "temporaryEncodingFormats": encoderFormats,
      // These are sensor photo-size metadata, not ARKit selectable outputs.
      "resolutionsByFormat": ["jpeg": hardwareDimensions],
      "pipelineResolutionsByFormat": ["jpeg": stillDimensions],
      "depthSupport": ["depthApi": capabilities.sceneDepthSupported,
                       "rawDepth": false, "lidar": capabilities.hasLiDARDepth],
      "controls": [
        "iso": value(device?.isExposureModeSupported(.custom)),
        "exposureTime": value(device?.isExposureModeSupported(.custom)),
        "focus": value(device?.isLockingFocusWithCustomLensPositionSupported),
        "whiteBalance": value(device?.isLockingWhiteBalanceWithCustomDeviceGainsSupported),
        "exposureCompensation": value(device.map { $0.minExposureTargetBias != $0.maxExposureTargetBias }),
      ],
      "hdrSupport": hdrSupport(
        supportsBalancedAPI: supportsBalancedAPI,
        highResolutionFrameCapture: capabilities.supportsHighResolutionFrameCapture,
        videoHDR: capabilities.videoFormats.contains { $0.isVideoHDRSupported }),
    ]
  }

  /// The values on this device: ARKit's formats, the configurable camera's
  /// active format and the newest frame of the running capture view.
  static func current() -> CameraCapabilitySnapshot {
    guard ARWorldTrackingConfiguration.isSupported else {
      return CameraCapabilitySnapshot(stillSize: nil, videoSizes: [], isoRange: nil, exposureRange: nil, intrinsics: nil)
    }
    let formats = ARWorldTrackingConfiguration.supportedVideoFormats
    var still: CGSize?
    if #available(iOS 26.0, *), let format = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing {
      let dimensions = format.defaultPhotoSettings.maxPhotoDimensions
      if dimensions.width > 0 { still = CGSize(width: Int(dimensions.width), height: Int(dimensions.height)) }
    }
    // Before iOS 26 ARKit exposes video geometry here, not the delivered
    // high-resolution still geometry. Leave it unknown until a real probe.
    let device = ARWorldTrackingConfiguration.configurableCaptureDeviceForPrimaryCamera
    let iso = device.map { $0.activeFormat.minISO...$0.activeFormat.maxISO }
    let exposure = device.map { $0.activeFormat.minExposureDuration.seconds...$0.activeFormat.maxExposureDuration.seconds }
    var intrinsics: [String: Any]?
    if let frame = ARFrameSourceRegistry.shared.current?.currentFrame, !(ARFrameSourceRegistry.shared.current?.replaysRecordedStills ?? true) {
      let width = CVPixelBufferGetWidth(frame.capturedImage), height = CVPixelBufferGetHeight(frame.capturedImage)
      if let k = StillIntrinsics.scaled(frame.intrinsics, imageResolution: frame.imageResolution, width: width, height: height) {
        intrinsics = NativeCaptureChannel.intrinsicsMap(k, width: width, height: height)
      }
    }
    return CameraCapabilitySnapshot(
      stillSize: still, videoSizes: formats.map { $0.imageResolution }, isoRange: iso, exposureRange: exposure,
      intrinsics: intrinsics)
  }
}
