import ARKit
import CoreImage
import Foundation
import ImageIO
import simd

/// Camera intrinsics of a still: ARKit reports them for the camera's image
/// resolution; a still of another size scales each axis.
enum StillIntrinsics {
  /// fx, fy, cx, cy of [intrinsics] (column-major K) for [imageResolution]
  /// scaled to a still of [width] x [height].
  static func scaled(_ intrinsics: simd_float3x3, imageResolution: CGSize, width: Int, height: Int) -> simd_float4? {
    guard imageResolution.width > 0, imageResolution.height > 0, width > 0, height > 0 else { return nil }
    let sx = Float(Double(width) / Double(imageResolution.width))
    let sy = Float(Double(height) / Double(imageResolution.height))
    let k = simd_float4(intrinsics[0][0] * sx, intrinsics[1][1] * sy, intrinsics[2][0] * sx, intrinsics[2][1] * sy)
    guard k.x.isFinite, k.y.isFinite, k.z.isFinite, k.w.isFinite, k.x > 0, k.y > 0 else { return nil }
    return k
  }
}

/// JPEG of a camera pixel buffer through Core Image (hardware assisted), in
/// the sensor orientation of the ARKit image (EXIF orientation 1, as the
/// intrinsics), with the capture metadata in EXIF.
enum JpegStillEncoder {
  private static let context = CIContext(options: [.cacheIntermediates: false])

  static func encode(_ pixelBuffer: CVPixelBuffer, quality: Int, metadata: StillExposureMetadata, deviceModel: String) -> Data? {
    guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
    var comment = "capture3d still highResolution=\(metadata.highResolution) exposureTimestampNs=\(metadata.exposureTimestampNs)"
    if let k = metadata.intrinsics {
      comment += " fx=\(k.x) fy=\(k.y) cx=\(k.z) cy=\(k.w) width=\(metadata.width) height=\(metadata.height)"
    }
    let properties: [String: Any] = [
      kCGImagePropertyOrientation as String: 1,
      kCGImagePropertyTIFFDictionary as String: [
        kCGImagePropertyTIFFMake as String: "Apple",
        kCGImagePropertyTIFFModel as String: deviceModel,
        kCGImagePropertyTIFFOrientation as String: 1,
      ],
      kCGImagePropertyExifDictionary as String: [
        kCGImagePropertyExifUserComment as String: comment,
        kCGImagePropertyExifPixelXDimension as String: metadata.width,
        kCGImagePropertyExifPixelYDimension as String: metadata.height,
      ],
    ]
    let image = CIImage(cvPixelBuffer: pixelBuffer, options: [.properties: properties])
    let options: [CIImageRepresentationOption: Any] = [
      CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): Double(min(max(quality, 10), 100)) / 100,
    ]
    return context.jpegRepresentation(of: image, colorSpace: space, options: options)
  }
}

/// One encoded still with what the camera knew about it.
struct EncodedStill {
  var jpeg: Data
  var metadata: StillExposureMetadata
  var isTracking: Bool
  var sharpness: LaplacianSharpness.Result?
}

struct StillCaptureFailure: Error {
  var reason: String
}

/// The iOS still camera of the capture view: `captureHighResolutionFrame` on
/// the view's frame source (the live `ARSession`: 12 MP on the iPhone 12 Pro,
/// about 100 ms, no preview loss; a recorded session: its nearest recorded
/// still), the exposed frame's own camera for pose and intrinsics scaled to
/// the still, and the frame timestamp as the exposure time. When the
/// high-resolution frame is unavailable or fails, the current video frame (if
/// at most 0.5 s old) is used at video resolution and flagged
/// (`highResolution = false`). Encoding and sharpness run on a background
/// queue, never on the main thread.
final class HighResolutionStillExposure: StillExposurePort {
  struct Counters {
    var highResolution = 0
    var videoFallback = 0
    var failures = 0
    var lastEncodeMs: Double?
    var lastTapToExposureMs: Double?
    var lastWidth = 0
    var lastHeight = 0
  }

  /// A video frame older than this (the session paused or stalled) is never
  /// used as the fallback still: its pose would not be the shutter's.
  static let maximumFallbackFrameAge: TimeInterval = 0.5

  private let frameSource: () -> ARFrameSource?
  private var configuredJpegQuality: Int
  private let deviceModel: String
  private let encodeQueue = DispatchQueue(label: "capture3d.still-encode", qos: .userInitiated)
  private let lock = NSLock()
  private var cancelled: [CaptureAttemptQualifier] = []
  private var counters = Counters()

  init(frameSource: @escaping () -> ARFrameSource?, jpegQuality: Int,
       deviceModel: String = ARCameraCapabilities.hardwareModelIdentifier()) {
    self.frameSource = frameSource
    self.configuredJpegQuality = jpegQuality
    self.deviceModel = deviceModel
  }

  /// JPEG quality of the next encode; a repeated initializeCapture updates it
  /// so the staged route, the durable route and the snapshot share one exposure.
  var jpegQuality: Int {
    get { lock.lock(); defer { lock.unlock() }; return configuredJpegQuality }
    set { lock.lock(); configuredJpegQuality = newValue; lock.unlock() }
  }

  var snapshot: Counters {
    lock.lock(); defer { lock.unlock() }
    return counters
  }

  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool {
    guard required == [.jpeg] else { return false }
    return captureStill(isCancelled: { [weak self] in self?.isCancelled(qualifier) ?? true }) { result in
      switch result {
      case .success(let still):
        callback(.components(StillComponentSet(qualifier: qualifier, components: [(.jpeg, [UInt8](still.jpeg))], metadata: still.metadata)))
      case .failure(let failure):
        callback(.failure(qualifier, failure.reason))
      }
    }
  }

  func cancelExposure(_ qualifier: CaptureAttemptQualifier) {
    lock.lock()
    if !cancelled.contains(qualifier) { cancelled.append(qualifier) }
    if cancelled.count > 16 { cancelled.removeFirst(cancelled.count - 16) }
    lock.unlock()
  }

  /// One encoded still. Returns false when no frame source is available;
  /// otherwise [completion] runs exactly once on the encode queue.
  func captureStill(isCancelled: @escaping () -> Bool = { false }, completion: @escaping (Result<EncodedStill, StillCaptureFailure>) -> Void) -> Bool {
    guard let source = frameSource() else { return false }
    let requestedAt = DispatchTime.now().uptimeNanoseconds
    source.captureHighResolutionFrame { [weak self, weak source] frame, error in
      guard let self = self else { return completion(.failure(StillCaptureFailure(reason: "closed"))) }
      if let frame = frame {
        self.process(frame, highResolution: true, requestedAt: requestedAt, isCancelled: isCancelled, completion: completion)
      } else if let current = source?.currentFrame,
                ProcessInfo.processInfo.systemUptime - current.timestamp < Self.maximumFallbackFrameAge {
        NSLog("capture3d still: high-resolution frame unavailable (%@); using the video frame", String(describing: error))
        self.process(current, highResolution: false, requestedAt: requestedAt, isCancelled: isCancelled, completion: completion)
      } else {
        self.record { $0.failures += 1 }
        completion(.failure(StillCaptureFailure(reason: "high-resolution-frame-unavailable")))
      }
    }
    return true
  }

  private func process(_ frame: CaptureFrame, highResolution: Bool, requestedAt: UInt64, isCancelled: @escaping () -> Bool,
                       completion: @escaping (Result<EncodedStill, StillCaptureFailure>) -> Void) {
    let pixelBuffer = frame.capturedImage
    let timestamp = frame.timestamp
    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    let tracking = frame.trackingState
    let tapToExposureMs = (timestamp * 1e9 - Double(requestedAt)) / 1e6
    var metadata = StillExposureMetadata(
      exposureTimestampNs: Int64((timestamp * 1e9).rounded()),
      cameraToWorld: tracking == .notAvailable ? nil : frame.cameraTransform,
      intrinsics: StillIntrinsics.scaled(frame.intrinsics, imageResolution: frame.imageResolution, width: width, height: height),
      width: width, height: height, sharpness: nil, highResolution: highResolution)
    let isTracking: Bool
    if case .normal = tracking { isTracking = true } else { isTracking = false }
    encodeQueue.async { [weak self] in
      guard let self = self else { return completion(.failure(StillCaptureFailure(reason: "closed"))) }
      if isCancelled() { return completion(.failure(StillCaptureFailure(reason: "cancelled"))) }
      let started = DispatchTime.now().uptimeNanoseconds
      guard let jpeg = JpegStillEncoder.encode(pixelBuffer, quality: self.jpegQuality, metadata: metadata, deviceModel: self.deviceModel) else {
        self.record { $0.failures += 1 }
        return completion(.failure(StillCaptureFailure(reason: "jpeg-encode-failed")))
      }
      let encodeMs = Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6
      let sharpness = LaplacianSharpness.analyze(jpeg: jpeg)
      metadata.sharpness = sharpness?.score
      self.record {
        if highResolution { $0.highResolution += 1 } else { $0.videoFallback += 1 }
        $0.lastEncodeMs = encodeMs
        $0.lastTapToExposureMs = tapToExposureMs
        $0.lastWidth = width
        $0.lastHeight = height
      }
      completion(.success(EncodedStill(jpeg: jpeg, metadata: metadata, isTracking: isTracking, sharpness: sharpness)))
    }
  }

  private func isCancelled(_ qualifier: CaptureAttemptQualifier) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return cancelled.contains(qualifier)
  }

  private func record(_ update: (inout Counters) -> Void) {
    lock.lock(); update(&counters); lock.unlock()
  }
}
