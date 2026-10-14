import ARKit
import Foundation
import simd

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

  init(deliver: @escaping (Data) -> Void) {
    self.deliver = deliver
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

  func frameSource(_ source: ARFrameSource, didUpdate frame: ARFrame) {
    let timestampNs = PoseWireSample.nanoseconds(frame.timestamp)
    guard let sequence = admit(timestampNs: timestampNs) else { return }
    let camera = frame.camera
    let sample = PoseWireSample(
      sequence: sequence,
      systemTimestampMs: Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)),
      timestampNs: timestampNs,
      cameraTransform: camera.transform,
      trackingState: camera.trackingState)
    deliver(PoseBatchWire.encode(sample))
  }

  func frameSourceDidStop(_ source: ARFrameSource) {
    lock.lock()
    limiter.reset()
    lock.unlock()
  }
}
