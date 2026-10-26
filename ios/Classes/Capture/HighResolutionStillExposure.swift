import ARKit
import CoreImage
import Foundation
import ImageIO
import simd
import UIKit

/// Camera intrinsics of a still: ARKit reports them for the camera's image
/// resolution; a still of another size scales each axis. Pixel centres are
/// at integer coordinates (OpenCV; docs/02-architecture.md, Coordinate
/// frames), so the principal point scales as `(c + 0.5) * s - 0.5`.
enum StillIntrinsics {
  /// Name of this convention in stored records and on the wire.
  static let convention = "opencv_pinhole_v1"

  /// fx, fy, cx, cy of [intrinsics] (column-major K) for [imageResolution]
  /// scaled to a still of [width] x [height].
  static func scaled(_ intrinsics: simd_float3x3, imageResolution: CGSize, width: Int, height: Int) -> simd_float4? {
    guard imageResolution.width > 0, imageResolution.height > 0, width > 0, height > 0 else { return nil }
    let sx = Float(Double(width) / Double(imageResolution.width))
    let sy = Float(Double(height) / Double(imageResolution.height))
    let k = simd_float4(
      intrinsics[0][0] * sx, intrinsics[1][1] * sy,
      (intrinsics[2][0] + 0.5) * sx - 0.5, (intrinsics[2][1] + 0.5) * sy - 0.5)
    guard k.x.isFinite, k.y.isFinite, k.z.isFinite, k.w.isFinite, k.x > 0, k.y > 0 else { return nil }
    return k
  }
}

/// JPEG of a camera pixel buffer through Core Image (hardware assisted), in
/// the sensor orientation of the ARKit image (EXIF orientation 1, as the
/// intrinsics), with the capture metadata in EXIF.
enum StillImageEncoder {
  private static let context = CIContext(options: [.cacheIntermediates: false])

  static func encode(_ pixelBuffer: CVPixelBuffer, quality: Int, metadata: StillExposureMetadata, deviceModel: String, format: String = "jpeg") -> Data? {
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
    if format == "heic" {
      return context.heifRepresentation(of: image, format: .RGBA8, colorSpace: space, options: options)
    }
    return context.jpegRepresentation(of: image, colorSpace: space, options: options)
  }
}

/// One encoded still with what the camera knew about it.
struct EncodedStill {
  var bytes: Data
  var format: String = "jpeg"
  var metadata: StillExposureMetadata
  var isTracking: Bool
  var sharpness: LaplacianSharpness.Result?
  /// Runtime measurement only; never serialized into a capture record.
  var encodingDurationUs: Int = 0
}

struct StillCaptureFailure: Error {
  var reason: String
}

/// The still a manual shutter takes on iOS (`initializeCapture`
/// `stillCaptureMode`; default `single`). Automatic captures always take the
/// fast in-AR still. Every mode runs in the running AR session; the session
/// is never paused for a still.
enum StillCaptureMode: String, CaseIterable {
  /// `captureHighResolutionFrame`: one 12 MP frame, about 100 ms.
  case single
  /// Several high-resolution frames in sequence ([BurstSettings]), each
  /// with its own frame's pose, intrinsics and timestamp.
  case burst
  /// In-AR multi-frame processing (`.balanced`), the in-AR HDR: about 490 ms.
  case inArQuality

  static func parse(_ value: Any?) -> StillCaptureMode? {
    guard let text = value as? String else { return value == nil ? .single : nil }
    return StillCaptureMode(rawValue: text)
  }

  /// The mode a view takes for [requested], with the stable reason code
  /// when it refuses it: a replayed recording without stills answers every
  /// still request with a video frame, so burst and quality stay single
  /// (`recordedPlaybackWithoutStills`).
  static func effective(_ requested: StillCaptureMode, replaysRecordedStills: Bool,
                        highResolution: Bool) -> (mode: StillCaptureMode, refusal: String?) {
    guard requested != .single, replaysRecordedStills, !highResolution else { return (requested, nil) }
    return (.single, "recordedPlaybackWithoutStills")
  }
}

/// The iOS still camera of the capture view: `captureHighResolutionFrame` on
/// the view's frame source (the live `ARSession`: 12 MP on the iPhone 12 Pro,
/// about 100 ms, no preview loss; a recorded session: its nearest recorded
/// still), the exposed frame's own camera for pose and intrinsics scaled to
/// the still, and the frame timestamp as the exposure time. When the
/// high-resolution frame is unavailable or fails, the current video frame (if
/// at most 0.5 s old) is used at video resolution only for Automatic, and flagged
/// (`highResolution = false`). An explicit session-bound still size forbids that
/// fallback and rejects mismatching high-resolution geometry before encoding.
/// Encoding and sharpness run on a background
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
  private let cameraAccess: StillCameraAccess?
  private let requireRequestedQuality: Bool
  /// Immutable geometry selected by the owning AR session, not a cache hint.
  let exactStillResolution: CGSize?
  private let shutterFeedback: () -> Void
  private var configuredJpegQuality: Int
  private var configuredMode: StillCaptureMode = .single
  private var configuredBurst = BurstSettings()
  private var configuredFormat = "jpeg"
  private let burstQueue = DispatchQueue(label: "capture3d.still-burst", qos: .userInitiated)
  private let deviceModel: String
  private let encodeQueue = DispatchQueue(label: "capture3d.still-encode", qos: .userInitiated)
  private let lock = NSLock()
  /// Each camera request retains its own monotonic cancellation until its
  /// callback. A bounded history can forget a queued request before ARKit runs it.
  private final class Cancellation {
    private let lock = NSLock()
    private var cancelled = false
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
  }
  private var cancellations: [CaptureAttemptQualifier: Cancellation] = [:]
  private var counters = Counters()

  init(frameSource: @escaping () -> ARFrameSource?, jpegQuality: Int, requireRequestedQuality: Bool = false,
       exactStillResolution: CGSize? = nil, cameraAccess: StillCameraAccess? = nil,
       deviceModel: String = ARCameraCapabilities.hardwareModelIdentifier(),
       // The shared coordinator confirms quality-qualified retained images.
       // Tests may still observe physical exposure callbacks independently.
       shutterFeedback: @escaping () -> Void = {}) {
    self.frameSource = frameSource
    self.cameraAccess = cameraAccess
    self.requireRequestedQuality = requireRequestedQuality
    self.exactStillResolution = exactStillResolution
    self.shutterFeedback = shutterFeedback
    self.configuredJpegQuality = jpegQuality
    self.deviceModel = deviceModel
  }

  /// Image count and spacing of a burst shot.
  var burst: BurstSettings {
    get { lock.lock(); defer { lock.unlock() }; return configuredBurst }
    set { lock.lock(); configuredBurst = newValue; lock.unlock() }
  }

  /// Still of the next manual shutter.
  var mode: StillCaptureMode {
    get { lock.lock(); defer { lock.unlock() }; return configuredMode }
    set { lock.lock(); configuredMode = newValue; lock.unlock() }
  }

  /// JPEG quality of the next encode; a repeated initializeCapture updates it
  /// so the staged route, the durable route and the snapshot share one exposure.
  var jpegQuality: Int {
    get { lock.lock(); defer { lock.unlock() }; return configuredJpegQuality }
    set { lock.lock(); configuredJpegQuality = newValue; lock.unlock() }
  }

  /// Encoded primary image, fixed for one initialized camera owner.
  var format: String {
    get { lock.lock(); defer { lock.unlock() }; return configuredFormat }
    set { lock.lock(); configuredFormat = newValue; lock.unlock() }
  }

  var snapshot: Counters {
    lock.lock(); defer { lock.unlock() }
    return counters
  }

  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool {
    requestExposure(qualifier, required: required, lane: .automatic, callback: callback)
  }

  /// Manual shutters take the configured [mode]; Auto takes the fast still.
  func requestExposure(_ qualifier: CaptureAttemptQualifier, required: Set<CaptureComponentKind>, lane: CaptureLane,
                       callback: @escaping (StillExposureOutcome) -> Void) -> Bool {
    guard required == (format == "heic" ? [.heic] : [.jpeg]) else { return false }
    let mode = lane == .manual ? self.mode : .single
    if mode == .burst || mode == .inArQuality {
      guard format == "jpeg", frameSource() != nil else { return false }
    }
    let cancellation = Cancellation()
    lock.lock()
    guard cancellations[qualifier] == nil else { lock.unlock(); return false }
    cancellations[qualifier] = cancellation
    lock.unlock()
    let isCancelled: () -> Bool = { cancellation.isCancelled }
    let finish: (StillExposureOutcome) -> Void = { [weak self] outcome in
      self?.forgetCancellation(qualifier, owner: cancellation)
      callback(outcome)
    }
    if mode == .burst || mode == .inArQuality {
      let settings = mode == .burst ? burst : BurstSettings(imageCount: 1, intervalMs: 0)
      captureShot(mode: mode == .burst ? .burst : .quality, settings: settings, isCancelled: isCancelled) { result in
        switch result {
        case .success(let shot):
          finish(StillImageQualityGate.outcome(for: StillComponentSet(
            qualifier: qualifier, components: [(.jpeg, shot.first.jpeg)], metadata: shot.first.metadata, shot: shot)))
        case .failure(let failure):
          finish(.failure(qualifier, failure.reason))
        }
      }
      return true
    }
    let requested = captureStill(quality: .fast, isCancelled: isCancelled) { result in
      switch result {
      case .success(let still):
        finish(StillImageQualityGate.outcome(for: StillComponentSet(
          qualifier: qualifier, components: [(still.format == "heic" ? .heic : .jpeg, [UInt8](still.bytes))], metadata: still.metadata)))
      case .failure(let failure):
        finish(.failure(qualifier, failure.reason))
      }
    }
    if !requested { forgetCancellation(qualifier, owner: cancellation) }
    return requested
  }

  func cancelExposure(_ qualifier: CaptureAttemptQualifier) {
    lock.lock()
    let cancellation = cancellations.removeValue(forKey: qualifier)
    lock.unlock()
    cancellation?.cancel()
  }

  private func forgetCancellation(_ qualifier: CaptureAttemptQualifier, owner: Cancellation) {
    lock.lock(); defer { lock.unlock() }
    if cancellations[qualifier] === owner { cancellations.removeValue(forKey: qualifier) }
  }

  /// One encoded still. Returns false when no frame source is available;
  /// otherwise [completion] runs exactly once on the encode queue.
  func captureStill(quality: StillFrameQuality = .fast, isCancelled: @escaping () -> Bool = { false },
                    frameCaptured: (() -> Void)? = nil,
                    completion: @escaping (Result<EncodedStill, StillCaptureFailure>) -> Void) -> Bool {
    guard let source = frameSource() else { return false }
    let requestedAt = DispatchTime.now().uptimeNanoseconds
    let receive: (CaptureFrame?, Error?) -> Void = { [weak self, weak source] frame, error in
      guard let self = self else { return completion(.failure(StillCaptureFailure(reason: "closed"))) }
      if isCancelled() { return completion(.failure(StillCaptureFailure(reason: "cancelled"))) }
      // A stopped/closed owner is a lifecycle result, never permission to
      // substitute a still from a fresh-looking pre-stop video frame.
      if let failure = error as? StillCaptureFailure { return completion(.failure(failure)) }
      if let frame = frame {
        if let exact = self.exactStillResolution,
           (CVPixelBufferGetWidth(frame.capturedImage) != Int(exact.width)
            || CVPixelBufferGetHeight(frame.capturedImage) != Int(exact.height)) {
          self.record { $0.failures += 1 }
          return completion(.failure(StillCaptureFailure(reason: "still-resolution-mismatch")))
        }
        if !isCancelled(), source?.replaysRecordedStills == false {
          (frameCaptured ?? self.shutterFeedback)()
        }
        self.process(frame, highResolution: true, requestedAt: requestedAt, isCancelled: isCancelled, completion: completion)
      } else if !self.requireRequestedQuality, self.exactStillResolution == nil,
                let current = source?.currentFrame,
                ProcessInfo.processInfo.systemUptime - current.timestamp < Self.maximumFallbackFrameAge {
        NSLog("capture3d still: high-resolution frame unavailable (%@); using the video frame", String(describing: error))
        if !isCancelled(), source?.replaysRecordedStills == false {
          (frameCaptured ?? self.shutterFeedback)()
        }
        self.process(current, highResolution: false, requestedAt: requestedAt, isCancelled: isCancelled, completion: completion)
      } else {
        self.record { $0.failures += 1 }
        completion(.failure(StillCaptureFailure(reason: "high-resolution-frame-unavailable")))
      }
    }
    let request: (@escaping StillCameraAccess.FrameCompletion) -> Void = { completion in
      guard !isCancelled() else { return completion(nil, StillCaptureFailure(reason: "cancelled")) }
      if self.requireRequestedQuality {
        source.captureHighResolutionFrameForProfile(quality: quality, completion: completion)
      } else {
        source.captureHighResolutionFrame(quality: quality, completion: completion)
      }
    }
    if let cameraAccess = cameraAccess { cameraAccess.capture(request, completion: receive) }
    else { request(receive) }
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
      let format = self.format
      guard let jpeg = StillImageEncoder.encode(pixelBuffer, quality: self.jpegQuality, metadata: metadata, deviceModel: self.deviceModel, format: format) else {
        self.record { $0.failures += 1 }
        return completion(.failure(StillCaptureFailure(reason: "image-encode-failed")))
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
      completion(.success(EncodedStill(bytes: jpeg, format: format, metadata: metadata, isTracking: isTracking, sharpness: sharpness,
                                      encodingDurationUs: Int(encodeMs * 1_000))))
    }
  }

  /// One shot of [settings].imageCount stills in sequence inside the
  /// running session (balanced quality for a quality shot), each from its
  /// own frame; [BurstSettings.intervalMs] passes between one still's end and
  /// the next request. Every image keeps its own pose, intrinsics, timestamp
  /// and tracking; an image whose frame was not tracking normally is kept
  /// and flagged; an exposure equal to an earlier image's (a replayed
  /// recording's nearest still) ends the burst. A failed first image fails the shot; a later failure ends
  /// the burst with the images taken so far.
  func captureShot(mode: StillShotMode, settings: BurstSettings, isCancelled: @escaping () -> Bool = { false },
                   didEncode: ((Int) -> Void)? = nil,
                   completion: @escaping (Result<CapturedShot, StillCaptureFailure>) -> Void) {
    let quality: StillFrameQuality = mode == .quality ? .balanced : .fast
    var images: [ShotImage] = []
    // A burst may stop early on a failed/repeated frame. Confirm only once its
    // terminal shot is known, so the motor cannot shake a subsequent exposure.
    // This follows encoding/analysis, but never waits for durable storage.
    // Ordinary single/quality captures still confirm at the frame callback.
    let liveBurst = mode == .burst && frameSource()?.replaysRecordedStills == false
    func finish(_ result: Result<CapturedShot, StillCaptureFailure>) {
      if liveBurst, !isCancelled(), case .success(let shot) = result, !shot.images.isEmpty {
        shutterFeedback()
      }
      completion(result)
    }
    func next() {
      if isCancelled() { return finish(.failure(StillCaptureFailure(reason: "cancelled"))) }
      let started = captureStill(quality: quality, isCancelled: isCancelled,
                                 frameCaptured: mode == .burst ? {} : nil) { [weak self] result in
        switch result {
        case .success(let still):
          didEncode?(still.encodingDurationUs)
          // A replay answers close requests with the same recorded still:
          // a repeated exposure ends the burst instead of doubling a view.
          if images.contains(where: { $0.metadata.exposureTimestampNs == still.metadata.exposureTimestampNs }) {
            return finish(.success(CapturedShot(shotId: UUID(), mode: mode, images: images)))
          }
          images.append(ShotImage(
            index: images.count, jpeg: [UInt8](still.bytes), metadata: still.metadata, trackingNormal: still.isTracking))
          if images.count < settings.imageCount, let self = self {
            self.burstQueue.asyncAfter(deadline: .now() + .milliseconds(settings.intervalMs)) { next() }
          } else {
            finish(.success(CapturedShot(shotId: UUID(), mode: mode, images: images)))
          }
        case .failure(let failure):
          if self?.exactStillResolution != nil || images.isEmpty || failure.reason == "cancelled" {
            return finish(.failure(failure))
          }
          finish(.success(CapturedShot(shotId: UUID(), mode: mode, images: images)))
        }
      }
      if !started {
        finish(images.isEmpty ? .failure(StillCaptureFailure(reason: "closed"))
                                  : .success(CapturedShot(shotId: UUID(), mode: mode, images: images)))
      }
    }
    burstQueue.async { next() }
  }

  private func record(_ update: (inout Counters) -> Void) {
    lock.lock(); update(&counters); lock.unlock()
  }
}
