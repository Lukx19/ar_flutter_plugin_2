import Foundation

/// Camera tracking state as carried by the `pose_batch_v2` wire (low byte of
/// the flags field). Values match the Android `PoseDataExtractor.latestPacked`
/// encoding and the Dart `ARFramePose.fromPacked` decoder.
enum PoseWireTrackingState: Int32 {
  case tracking = 1
  case paused = 2
  case stopped = 3
  case synthetic = 4
}

/// One camera pose in the tracking (ARCore GL / ARKit) camera-to-world
/// convention: +X right, +Y up, -Z forward in the sensor orientation.
struct PoseWireSample: Equatable {
  var sequence: Int64
  /// Wall clock milliseconds since the Unix epoch when the frame was handled.
  var systemTimestampMs: Int64
  /// Sensor (frame) timestamp in nanoseconds.
  var timestampNs: Int64
  var bindingGeneration: Int64 = 0
  var groupGeneration: Int64 = 0
  var confidence: Float
  var trackingState: PoseWireTrackingState
  var isTracking: Bool
  var isSynthetic: Bool = false
  /// Translation x, y, z in metres.
  var position: [Float]
  /// Rotation quaternion x, y, z, w.
  var rotation: [Float]
  /// Column-major 4x4 camera-to-world transform.
  var transform: [Float]
}

/// Little-endian fixed records of 236 bytes: five i64 metadata fields,
/// confidence f32, flags and source i32, then the OpenCV pose (position,
/// quaternion, column-major transform) and the tracking pose (same layout).
/// Byte-for-byte equal to the Android `PackedPoseWireV2` sample.
enum PoseBatchWire {
  static let version = "pose_batch_v2"
  static let sampleBytes = 236
  static let trackingFlag: Int32 = 256

  static func encode(_ sample: PoseWireSample) -> Data {
    precondition(sample.position.count == 3 && sample.rotation.count == 4 && sample.transform.count == 16)
    var bytes = [UInt8](repeating: 0, count: sampleBytes)
    func putLong(_ offset: Int, _ value: Int64) {
      let bits = UInt64(bitPattern: value)
      for i in 0..<8 { bytes[offset + i] = UInt8(truncatingIfNeeded: bits >> UInt64(i * 8)) }
    }
    func putInt(_ offset: Int, _ value: Int32) {
      let bits = UInt32(bitPattern: value)
      for i in 0..<4 { bytes[offset + i] = UInt8(truncatingIfNeeded: bits >> UInt32(i * 8)) }
    }
    func putFloat(_ offset: Int, _ value: Float) {
      putInt(offset, Int32(bitPattern: value.bitPattern))
    }

    let openCvTransform = openCvCameraToWorld(sample.transform)
    let openCvRotation = quaternion(fromColumnMajor: openCvTransform)

    putLong(0, sample.sequence)
    putLong(8, sample.systemTimestampMs)
    putLong(16, sample.timestampNs)
    putLong(24, sample.bindingGeneration)
    putLong(32, sample.groupGeneration)
    putFloat(40, sample.confidence)
    putInt(44, sample.trackingState.rawValue | (sample.isTracking ? trackingFlag : 0))
    putInt(48, sample.isSynthetic ? 1 : 0)
    var offset = 52
    for i in 12...14 { putFloat(offset, openCvTransform[i]); offset += 4 }
    for value in openCvRotation { putFloat(offset, value); offset += 4 }
    for value in openCvTransform { putFloat(offset, value); offset += 4 }
    for value in sample.position { putFloat(offset, value); offset += 4 }
    for value in sample.rotation { putFloat(offset, value); offset += 4 }
    for value in sample.transform { putFloat(offset, value); offset += 4 }
    return Data(bytes)
  }

  /// Tracking camera-to-world to OpenCV camera-to-world: the camera Y and Z
  /// axes (columns 1 and 2) flip sign, as on Android.
  static func openCvCameraToWorld(_ transform: [Float]) -> [Float] {
    var converted = transform
    for row in 0...3 {
      converted[4 + row] = -converted[4 + row]
      converted[8 + row] = -converted[8 + row]
    }
    return converted
  }

  /// Quaternion x, y, z, w of the rotation part of a column-major transform.
  /// Same branch order and float arithmetic as the Android encoder, so equal
  /// inputs give equal bits.
  static func quaternion(fromColumnMajor t: [Float]) -> [Float] {
    let m00 = t[0], m11 = t[5], m22 = t[10]
    let trace = m00 + m11 + m22
    if trace > 0 {
      let scale = (trace + 1).squareRoot() * 2
      return [(t[6] - t[9]) / scale, (t[8] - t[2]) / scale, (t[1] - t[4]) / scale, 0.25 * scale]
    } else if m00 > m11 && m00 > m22 {
      let scale = (1 + m00 - m11 - m22).squareRoot() * 2
      return [0.25 * scale, (t[4] + t[1]) / scale, (t[8] + t[2]) / scale, (t[6] - t[9]) / scale]
    } else if m11 > m22 {
      let scale = (1 + m11 - m00 - m22).squareRoot() * 2
      return [(t[4] + t[1]) / scale, 0.25 * scale, (t[9] + t[6]) / scale, (t[8] - t[2]) / scale]
    } else {
      let scale = (1 + m22 - m00 - m11).squareRoot() * 2
      return [(t[8] + t[2]) / scale, (t[9] + t[6]) / scale, 0.25 * scale, (t[1] - t[4]) / scale]
    }
  }
}

/// At most one pose sample per 33.3 ms of sensor time (30 Hz), strictly
/// increasing timestamps; mirrors the Android `PoseUpdateRateLimiter`.
struct PoseRateLimiter {
  let minimumIntervalNs: Int64
  private var lastTimestampNs: Int64?

  init(maximumRateHz: Int64 = 30) {
    minimumIntervalNs = 1_000_000_000 / maximumRateHz
  }

  mutating func shouldEmit(_ timestampNs: Int64) -> Bool {
    if let last = lastTimestampNs {
      if timestampNs <= last || timestampNs - last < minimumIntervalNs { return false }
    }
    lastTimestampNs = timestampNs
    return true
  }

  mutating func reset() { lastTimestampNs = nil }
}

/// One `onPoseBatch` message: up to [capacity] concatenated samples.
struct PoseBatch: Equatable {
  let sampleCount: Int
  let sampleBytes: Data
  let droppedOldestCount: Int
}

/// Bounded acknowledged transport: one batch in flight; while it is
/// unacknowledged new samples queue (oldest dropped beyond [capacity]) and go
/// out together on the acknowledgement. Mirrors the Android
/// `PoseBatchDispatcher`. Not thread safe: use from one thread (main).
final class PoseBatchDispatcher {
  typealias Send = (_ batch: PoseBatch, _ acknowledge: @escaping () -> Void) -> Void

  private let send: Send
  private let capacity: Int
  private var pending: [Data] = []
  private var inFlight = false
  private var droppedSinceLastBatch = 0

  init(capacity: Int = 8, send: @escaping Send) {
    precondition(capacity > 0)
    self.capacity = capacity
    self.send = send
  }

  var pendingCount: Int { pending.count }
  var isInFlight: Bool { inFlight }

  func offer(_ sample: Data) {
    precondition(sample.count == PoseBatchWire.sampleBytes)
    if pending.count == capacity {
      pending.removeFirst()
      droppedSinceLastBatch += 1
    }
    pending.append(sample)
    dispatchIfIdle()
  }

  /// Drops queued samples. A batch already in flight stays in flight until
  /// Dart acknowledges it (every reply, error or not, acknowledges).
  func clear() {
    pending.removeAll()
    droppedSinceLastBatch = 0
  }

  private func dispatchIfIdle() {
    guard !inFlight, !pending.isEmpty else { return }
    let count = pending.count
    var bytes = Data(capacity: count * PoseBatchWire.sampleBytes)
    for sample in pending { bytes.append(sample) }
    pending.removeAll(keepingCapacity: true)
    let dropped = droppedSinceLastBatch
    droppedSinceLastBatch = 0
    inFlight = true
    send(PoseBatch(sampleCount: count, sampleBytes: bytes, droppedOldestCount: dropped)) { [weak self] in
      guard let self = self else { return }
      self.inFlight = false
      self.dispatchIfIdle()
    }
  }
}

/// Pose samples to `onPoseBatch` on the capture channel, main thread only.
/// Open between capture `initializeCapture` and capture `dispose`. While the
/// session is paused it drops every offer, including samples the delegate
/// queue handed to the main queue before the pause, so no pose reaches Dart
/// between `pauseSession` and `resumeSession`.
final class PoseBatchSink {
  private let dispatcher: PoseBatchDispatcher
  private(set) var isOpen = false
  private(set) var isPaused = false

  init(dispatcher: PoseBatchDispatcher) {
    self.dispatcher = dispatcher
  }

  func open() { isOpen = true }

  func close() {
    isOpen = false
    dispatcher.clear()
  }

  /// Drops queued samples and every later offer until [resume].
  func pause() {
    isPaused = true
    dispatcher.clear()
  }

  func resume() { isPaused = false }

  func offer(_ sample: Data) {
    guard isOpen, !isPaused else { return }
    dispatcher.offer(sample)
  }
}
