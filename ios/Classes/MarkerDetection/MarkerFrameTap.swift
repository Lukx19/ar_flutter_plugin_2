import ARKit
import CoreVideo
import Foundation
import simd

/// Live marker detection on the camera image, the iOS twin of the Android
/// `MarkerFrameTap` and `MarkerDetectionProducer`: an [ARFrameObserver] that,
/// at most [hz] times a second of frame time while the shell runs with marker
/// detection on, hands the Y plane of `capturedImage` (bi-planar YCbCr, read
/// in place: no copy) to a utility queue, runs native detection and pose, and
/// offers one ingest kind 5 message with the pose at the image timestamp.
///
/// At most one image is in flight: the tap keeps that one `CVPixelBuffer`
/// (not the frame) until its detection ends, and a frame due while the queue
/// is busy is skipped and counted, so the delegate queue never waits.
///
/// Failures are counted and recorded in the native session log as
/// `MARKER_DETECTION_FAILED` with the Android data keys: stage `detect`
/// (the detector refused the image; `detectionFailures`) or stage `capture`
/// (an unsupported pixel format or an unreadable Y plane; `captureFailures`),
/// plus `error`. The variant is `<stage>/<cause>` with a named iOS cause
/// where Android names the exception class; repeats are counted by the
/// session log's flood policy. As on Android, an image is skipped silently
/// while no detector is available (counted only in `detectorUnavailable`).
final class MarkerFrameTap: ARFrameObserver {
  static let defaultHz = 8

  private let settings: () -> MarkerDetectionSettings?
  private let sink: () -> IngestMessageSink?
  private let clock: IngestClock
  private let queue: DispatchQueue
  /// Created on the detection queue at the first image (AprilTag's decode
  /// table of ARUCO_MIP_36h12 takes about 8 MB).
  private let makeDetector: () -> MarkerFrameDetector?
  private var detector: MarkerFrameDetector?
  private let writer = MarkerDetectionMessageWriter()
  private let sessionLog: NativeSessionLogEmitter

  private let lock = NSLock()
  private var busy = false
  private var closed = false
  private var lastStartNs: Int64?
  private var appliedSize: Float = 0
  private var counters = Counters()

  struct Counters: Equatable {
    var images = 0
    var messages = 0
    var refused = 0
    var skippedBusy = 0
    /// Images skipped because no detector could be created (not a failure, as on Android).
    var detectorUnavailable = 0
    /// All failures: [detectionFailures] plus [captureFailures].
    var failures = 0
    /// The detector refused the image.
    var detectionFailures = 0
    /// The image could not be read (unsupported pixel format or no Y plane).
    var captureFailures = 0
  }

  /// Counters (locked copy).
  var snapshot: Counters { lock.lock(); defer { lock.unlock() }; return counters }

  init(
    makeDetector: @escaping () -> MarkerFrameDetector?, settings: @escaping () -> MarkerDetectionSettings?,
    sink: @escaping () -> IngestMessageSink?, clock: IngestClock,
    queue: DispatchQueue = DispatchQueue(label: "capture3d.markers.detect", qos: .utility),
    sessionLog: NativeSessionLogEmitter = .shared
  ) {
    self.sessionLog = sessionLog
    self.makeDetector = makeDetector
    self.settings = settings
    self.sink = sink
    self.clock = clock
    self.queue = queue
  }

  static func periodNs(hz: Int) -> Int64 {
    1_000_000_000 / Int64(min(30, max(1, hz > 0 ? hz : defaultHz)))
  }

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {
    guard let s = settings(), s.enabled else { return }
    let ts = Int64((frame.timestamp * 1e9).rounded())
    lock.lock()
    if closed { lock.unlock(); return }
    if let last = lastStartNs, ts >= last, ts - last < Self.periodNs(hz: s.hz) {
      lock.unlock()
      return
    }
    if busy {
      counters.skippedBusy += 1
      lock.unlock()
      return
    }
    let image = frame.capturedImage
    let format = CVPixelBufferGetPixelFormatType(image)
    guard format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
      || format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
      || format == kCVPixelFormatType_OneComponent8
    else {
      // An image the tap cannot read: Android's capture failure. The failed
      // attempt waits one period; the session log is called after unlocking.
      counters.failures += 1
      counters.captureFailures += 1
      let failures = counters.captureFailures
      lastStartNs = ts
      lock.unlock()
      sessionLog.failure(
        NativeSessionLogCodes.markerDetectionFailed, variant: "capture/UnsupportedFormat",
        message: "Marker image capture failed"
      ) {
        ["stage": "capture", "captureFailures": failures, "error": fourCharacterCode(format)]
      }
      return
    }
    busy = true
    lastStartNs = ts
    let dropped = UInt32(truncatingIfNeeded: counters.skippedBusy)
    lock.unlock()
    let k = frame.intrinsics
    let tracking = ingestTrackingState(frame.trackingState)
    let header = MarkerDetectionHeader(
      sampleNs: ts, acquireNs: clock.nowNs(), trackingState: tracking,
      poseMatch: tracking == IngestTrackingState.tracking
        ? IngestMessageLayout.poseMatchExact : IngestMessageLayout.poseMatchMissing,
      groupEpoch: s.groupEpoch & 0xffff, cameraToWorld: frame.cameraTransform,
      intrinsics: simd_float4(k[0][0], k[1][1], k[2][0], k[2][1]),
      imageWidth: Int(frame.imageResolution.width), imageHeight: Int(frame.imageResolution.height),
      dropped: dropped)
    let size = s.markerSizeMetres
    queue.async { [self] in
      detect(image, header: header, markerSize: size)
      lock.lock(); busy = false; lock.unlock()
    }
  }

  func frameSourceDidStop(_ source: ARFrameSource) {
    lock.lock(); lastStartNs = nil; lock.unlock()
  }

  /// Stops the tap; a running detection finishes and is still offered.
  func close() {
    lock.lock(); closed = true; lock.unlock()
  }

  /// Utility queue: detection, pose and the message for one image. The
  /// header's intrinsics are for its image size and are scaled to the Y
  /// plane when that differs (pixel centres at integer coordinates).
  func detect(_ image: CVPixelBuffer, header: MarkerDetectionHeader, markerSize: Float) {
    if detector == nil {
      detector = makeDetector()
      detector?.setFrameQuality()
    }
    guard let detector else {
      // Skipped silently, as Android skips while the detector is unavailable.
      lock.lock(); counters.detectorUnavailable += 1; lock.unlock()
      return
    }
    if markerSize > 0, markerSize != appliedSize {
      detector.setFrameMarkerSize(Double(markerSize))
      appliedSize = markerSize
    }
    CVPixelBufferLockBaseAddress(image, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(image, .readOnly) }
    let planar = CVPixelBufferIsPlanar(image)
    let base = planar ? CVPixelBufferGetBaseAddressOfPlane(image, 0) : CVPixelBufferGetBaseAddress(image)
    let width = planar ? CVPixelBufferGetWidthOfPlane(image, 0) : CVPixelBufferGetWidth(image)
    let height = planar ? CVPixelBufferGetHeightOfPlane(image, 0) : CVPixelBufferGetHeight(image)
    let stride = planar ? CVPixelBufferGetBytesPerRowOfPlane(image, 0) : CVPixelBufferGetBytesPerRow(image)
    guard let grey = base?.assumingMemoryBound(to: UInt8.self), width > 0, height > 0 else {
      lock.lock()
      counters.failures += 1
      counters.captureFailures += 1
      let failures = counters.captureFailures
      lock.unlock()
      sessionLog.failure(
        NativeSessionLogCodes.markerDetectionFailed, variant: "capture/ImageUnreadable",
        message: "Marker image capture failed"
      ) {
        ["stage": "capture", "captureFailures": failures, "error": "image plane unreadable (\(width)x\(height))"]
      }
      return
    }
    var h = header
    let sx = h.imageWidth > 0 ? Float(width) / Float(h.imageWidth) : 1
    let sy = h.imageHeight > 0 ? Float(height) / Float(h.imageHeight) : 1
    let k = h.intrinsics
    h.intrinsics = simd_float4(k.x * sx, k.y * sy, (k.z + 0.5) * sx - 0.5, (k.w + 0.5) * sy - 0.5)
    h.imageWidth = width
    h.imageHeight = height
    detector.setFrameCamera(fx: Double(h.intrinsics.x), fy: Double(h.intrinsics.y), cx: Double(h.intrinsics.z),
                            cy: Double(h.intrinsics.w))
    let count = detector.detect(UnsafePointer(grey), width: width, height: height, stride: stride)
    guard count >= 0 else {
      detectionFailed(cause: "DetectFailed", error: "detector returned \(count)")
      return
    }
    if count > 0 { detector.estimatePose() }
    let length = writer.write(h, records: count > 0 ? detector.records : nil, count: Int(count))
    let offered = sink()?.offer(writer.buffer, length: length) ?? false
    lock.lock()
    counters.images += 1
    if offered { counters.messages += 1 } else { counters.refused += 1 }
    lock.unlock()
  }

  /// Utility queue: counts a detection failure and records it (stage `detect`).
  private func detectionFailed(cause: String, error: String) {
    lock.lock()
    counters.images += 1
    counters.failures += 1
    counters.detectionFailures += 1
    let failures = counters.detectionFailures
    lock.unlock()
    sessionLog.failure(
      NativeSessionLogCodes.markerDetectionFailed, variant: "detect/\(cause)", message: "Marker detection failed"
    ) {
      ["stage": "detect", "detectionFailures": failures, "error": error]
    }
  }
}

/// The detector calls of the frame tap ([NativeMarkerDetector] in
/// production; tests inject a fake to drive detection failures).
protocol MarkerFrameDetector: AnyObject {
  /// Applies the tap's default quality thresholds.
  func setFrameQuality()
  func setFrameMarkerSize(_ metres: Double)
  func setFrameCamera(fx: Double, fy: Double, cx: Double, cy: Double)
  /// Detects on a grey image read in place; returns the count or a negative status.
  func detect(_ grey: UnsafePointer<UInt8>, width: Int, height: Int, stride: Int) -> Int32
  @discardableResult
  func estimatePose() -> Int32
  /// The 64 B contract records of the last detection.
  var records: UnsafeRawPointer { get }
}

extension NativeMarkerDetector: MarkerFrameDetector {
  func setFrameQuality() { setQuality() }
  func setFrameMarkerSize(_ metres: Double) { setMarkerSize(metres) }
  func setFrameCamera(fx: Double, fy: Double, cx: Double, cy: Double) { setCamera(fx: fx, fy: fy, cx: cx, cy: cy) }
}

/// The four-character code of a pixel format (for example `BGRA`), or its number.
func fourCharacterCode(_ format: OSType) -> String {
  let bytes = [24, 16, 8, 0].map { UInt8((format >> $0) & 0xff) }
  guard bytes.allSatisfy({ $0 >= 0x20 && $0 < 0x7f }) else { return "\(format)" }
  return String(decoding: bytes, as: UTF8.self)
}
