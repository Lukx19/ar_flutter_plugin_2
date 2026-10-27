import ARKit
import Foundation
import simd
import UIKit

extension PoseWireSample {
  /// A wire sample from an ARKit camera. `ARCamera.transform` is the
  /// camera-to-world transform in the sensor orientation with +Y up and -Z
  /// forward, the same convention as the ARCore camera pose on Android.
  init(
    sequence: Int64,
    systemTimestampMs: Int64,
    timestampNs: Int64,
    cameraTransform: simd_float4x4,
    trackingState: ARCamera.TrackingState
  ) {
    let columns = [cameraTransform.columns.0, cameraTransform.columns.1,
                   cameraTransform.columns.2, cameraTransform.columns.3]
    let transform = columns.flatMap { [$0.x, $0.y, $0.z, $0.w] }
    let (wireState, isTracking) = PoseWireSample.wireTrackingState(trackingState)
    self.init(
      sequence: sequence,
      systemTimestampMs: systemTimestampMs,
      timestampNs: timestampNs,
      confidence: isTracking ? 1 : 0,
      trackingState: wireState,
      isTracking: isTracking,
      position: [transform[12], transform[13], transform[14]],
      rotation: PoseBatchWire.quaternion(fromColumnMajor: transform),
      transform: transform)
  }

  /// ARKit `normal` is ARCore `TRACKING`. `limited` and `notAvailable` map to
  /// ARCore `PAUSED` (tracking temporarily lost or not yet established); the
  /// pose is not used for credit or capture while not tracking.
  static func wireTrackingState(_ state: ARCamera.TrackingState) -> (PoseWireTrackingState, Bool) {
    switch state {
    case .normal:
      return (.tracking, true)
    case .limited, .notAvailable:
      return (.paused, false)
    }
  }

  /// Sensor timestamp in nanoseconds from `ARFrame.timestamp` seconds.
  static func nanoseconds(_ seconds: TimeInterval) -> Int64 {
    Int64((seconds * 1_000_000_000).rounded())
  }
}

/// Builds `pose_batch_v2` samples from session frames at up to 30 Hz while
/// enabled (between capture `initializeCapture` and capture `dispose`).
/// Runs on the session delegate queue and copies the camera pose; the frame is
/// not kept. [deliver] receives each encoded 236-byte sample on that queue.
final class PoseStreamObserver: ARFrameObserver {
  private let lock = NSLock()
  private var enabled = false
  private var sequence: Int64 = 0
  private var limiter = PoseRateLimiter()
  private let deliver: (Data) -> Void
  private let deliverDisplaySample: ((Data, Double?, SemanticFloorSample?) -> Void)?
  private var displayOrientation: UIInterfaceOrientation?

  func updateDisplayOrientation(_ orientation: UIInterfaceOrientation?) {
    lock.lock(); defer { lock.unlock() }
    displayOrientation = orientation == .unknown ? nil : orientation
  }

  private func currentDisplayOrientation() -> UIInterfaceOrientation? {
    lock.lock(); defer { lock.unlock() }
    return displayOrientation
  }

  /// Rotation from optical right/up to displayed right/up, determined by
  /// ARKit's actual view matrix rather than a guessed portrait offset.
  static func displayRotation(camera: simd_float4x4, view: simd_float4x4) -> Double? {
    let display = simd_inverse(view)
    let rawRight = SIMD3<Float>(camera.columns.0.x, camera.columns.0.y, camera.columns.0.z)
    let rawUp = SIMD3<Float>(camera.columns.1.x, camera.columns.1.y, camera.columns.1.z)
    let right = SIMD3<Float>(display.columns.0.x, display.columns.0.y, display.columns.0.z)
    let rawForward = SIMD3<Float>(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z)
    let forward = SIMD3<Float>(display.columns.2.x, display.columns.2.y, display.columns.2.z)
    let cosine = simd_dot(right, rawRight), sine = simd_dot(right, rawUp)
    guard cosine.isFinite, sine.isFinite, simd_dot(forward, rawForward) >= 0.999,
      abs(cosine * cosine + sine * sine - 1) <= 0.001 else { return nil }
    return Double(atan2(sine, cosine))
  }

  init(deliverDisplaySample: ((Data, Double?, SemanticFloorSample?) -> Void)? = nil, deliver: @escaping (Data) -> Void) {
    self.deliver = deliver
    self.deliverDisplaySample = deliverDisplaySample
  }

  var isEnabled: Bool {
    lock.lock(); defer { lock.unlock() }
    return enabled
  }

  /// Starts a stream: sequence restarts at 1, rate window cleared.
  func enable() {
    lock.lock()
    enabled = true
    sequence = 0
    limiter.reset()
    lock.unlock()
  }

  func disable() {
    lock.lock()
    enabled = false
    limiter.reset()
    lock.unlock()
  }

  /// Returns the next sequence when a sample at [timestampNs] is due.
  func admit(timestampNs: Int64) -> Int64? {
    lock.lock(); defer { lock.unlock() }
    guard enabled, limiter.shouldEmit(timestampNs) else { return nil }
    sequence += 1
    return sequence
  }

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) {
    let timestampNs = PoseWireSample.nanoseconds(frame.timestamp)
    guard let sequence = admit(timestampNs: timestampNs) else { return }
    let sample = PoseWireSample(
      sequence: sequence,
      systemTimestampMs: Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)),
      timestampNs: timestampNs,
      cameraTransform: frame.cameraTransform,
      trackingState: frame.trackingState)
    let encoded = PoseBatchWire.encode(sample)
    if let deliverDisplaySample = deliverDisplaySample {
      let rotation = currentDisplayOrientation().flatMap {
        Self.displayRotation(camera: frame.cameraTransform, view: frame.viewMatrix(for: $0))
      }
      // ARCore has no semantic floor classification; its producer omits these facts.
      // A horizontal tabletop must never be reported as a verified floor.
      var floor: SemanticFloorSample?
      var largestArea: Double = 0
      if sample.isTracking {
        for case let plane as ARPlaneAnchor in frame.anchors where plane.classification == .floor && plane.alignment == .horizontal {
          let centre = plane.transform * SIMD4<Float>(plane.center.x, plane.center.y, plane.center.z, 1)
          let area = Double(plane.extent.x * plane.extent.z)
          let normalY = Double(plane.transform.columns.1.y)
          if area.isFinite && centre.y.isFinite && normalY.isFinite && area > largestArea {
            largestArea = area
            floor = SemanticFloorSample(anchorId: plane.identifier.uuidString, worldY: Double(centre.y), normalY: normalY, areaSquareMetres: area)
          }
        }
      }
      deliverDisplaySample(encoded, rotation, floor)
    } else {
      deliver(encoded)
    }
  }

  func frameSourceDidStop(_ source: ARFrameSource) {
    lock.lock()
    limiter.reset()
    lock.unlock()
  }
}
