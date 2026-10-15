import ARKit
import Compression
import Foundation
import simd
import XCTest
@testable import ar_flutter_plugin_2

/// Recorded session fixtures of the playback tests.
///
/// `room2_trimmed` is 24 frames (0.4 s) of the `room2-iphone12pro-20261014`
/// recording (frames 104-127, stills 0 and 1), made by
/// `scripts/ios/make_recorded_session_fixture.swift` in the parent
/// repository: frame records, depth and point chunks are the recorded
/// bytes; video and stills are re-encoded at 1/8 size. The folder
/// `test_assets/arkit_playback` is copied into the test bundle.
/// [SyntheticRecording] writes small recordings in the recorder's format
/// for cases the fixture does not contain.
enum RecordedSessionFixture {
  static let folder = "arkit_playback"
  static let room2 = "room2_trimmed"

  static func room2URL() throws -> URL {
    guard let base = GoldenFixture.bundle.resourceURL?
      .appendingPathComponent(folder, isDirectory: true)
      .appendingPathComponent(room2, isDirectory: true),
      FileManager.default.fileExists(atPath: base.appendingPathComponent("meta.json").path)
    else { throw RecordedSessionError.missingFile("\(folder)/\(room2) in the test bundle") }
    return base
  }

  /// A writable copy of the room2 fixture without [omitting] files.
  static func room2Copy(omitting: [String] = []) throws -> URL {
    let copy = FileManager.default.temporaryDirectory
      .appendingPathComponent("recorded-session-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.copyItem(at: try room2URL(), to: copy)
    for name in omitting { try FileManager.default.removeItem(at: copy.appendingPathComponent(name)) }
    return copy
  }
}

/// Writes recordings in the ARKit session recorder's format.
struct SyntheticRecording {
  struct Frame {
    var timestamp: TimeInterval
    var trackingState: UInt8 = 2
    var trackingReason: UInt8 = 0
    var translation = simd_float3(0, 0, 0)
    var depthIndex: UInt32?
  }

  var frames: [Frame] = []
  var depthChunks: [Data] = []

  static func put<T>(_ data: inout Data, _ value: T) {
    withUnsafeBytes(of: value) { data.append(contentsOf: $0) }
  }

  /// The recorder's frame record: 1920x1440 image, fx = fy = 1450.
  static func record(_ frame: Frame, index: UInt32) -> Data {
    var record = Data()
    put(&record, UInt32(0x4641_3343))
    put(&record, UInt16(1))
    put(&record, UInt16(frame.depthIndex == nil ? 0 : 2))
    put(&record, frame.timestamp)
    put(&record, index)
    record.append(frame.trackingState)
    record.append(frame.trackingReason)
    put(&record, UInt16(0))
    var transform = matrix_identity_float4x4
    transform.columns.3 = simd_float4(frame.translation, 1)
    for c in 0..<4 { for r in 0..<4 { put(&record, transform[c][r]) } }
    let k = simd_float3x3(columns: (simd_float3(1450, 0, 0), simd_float3(0, 1450, 0), simd_float3(960, 720, 1)))
    for c in 0..<3 { for r in 0..<3 { put(&record, k[c][r]) } }
    put(&record, UInt32(1920))
    put(&record, UInt32(1440))
    put(&record, Float(0.01))
    put(&record, Float(0))
    put(&record, Float(-1))
    put(&record, Float(-1))
    put(&record, UInt32.max)
    put(&record, frame.depthIndex ?? UInt32.max)
    put(&record, UInt32.max)
    put(&record, UInt32.max)
    record.append(0)
    record.append(Data(count: 256 - record.count))
    return record
  }

  /// The recorder's encoding of one depth map (`ARKitSessionRecorder.depthBytes`
  /// and `writeDepth`): u16 millimetres rounded from f32 metres (0 for
  /// non-finite or non-positive), u8 confidence, LZFSE when it compresses.
  static func depthChunk(index: UInt32, timestamp: TimeInterval, width: Int, height: Int,
                         metres: [Float], confidence: [UInt8], compress: Bool = true) -> Data {
    var raw = Data(count: width * height * 3)
    raw.withUnsafeMutableBytes { dst in
      let mm = dst.bindMemory(to: UInt16.self)
      for i in 0..<(width * height) {
        let v = metres[i]
        mm[i] = v.isFinite && v > 0 ? UInt16(min(65535, (v * 1000).rounded())) : 0
      }
      for i in 0..<(width * height) { dst[width * height * 2 + i] = confidence[i] }
    }
    var payload = raw
    if compress {
      let capacity = raw.count + 4096
      var out = Data(count: capacity)
      let n = out.withUnsafeMutableBytes { dst in
        raw.withUnsafeBytes { src in
          compression_encode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, capacity,
                                    src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_LZFSE)
        }
      }
      if n > 0 { out.count = n; payload = out }
    }
    var chunk = Data()
    put(&chunk, UInt32(0x4441_3343))
    put(&chunk, index)
    put(&chunk, timestamp)
    put(&chunk, UInt16(width))
    put(&chunk, UInt16(height))
    put(&chunk, UInt32(payload.count))
    put(&chunk, UInt32(raw.count))
    put(&chunk, UInt32(payload.count == raw.count ? 0 : 1))
    chunk.append(payload)
    return chunk
  }

  /// Writes the recording to a new temporary folder.
  func write() throws -> URL {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent("synthetic-recording-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    var framesData = Data()
    for (i, frame) in frames.enumerated() { framesData.append(Self.record(frame, index: UInt32(i))) }
    try framesData.write(to: directory.appendingPathComponent("frames.bin"))
    try depthChunks.reduce(Data(), +).write(to: directory.appendingPathComponent("depth.bin"))
    let meta: [String: Any] = [
      "format": "capture3d-arkit-recording", "version": 1, "frameRecordBytes": 256,
      "firstTimestamp": frames.first?.timestamp ?? 0, "lastTimestamp": frames.last?.timestamp ?? 0,
      "frames": frames.count, "depthMaps": depthChunks.count, "pointClouds": 0, "stills": 0,
      "videoSegments": [[String: Any]](),
    ]
    try JSONSerialization.data(withJSONObject: meta).write(to: directory.appendingPathComponent("meta.json"))
    return directory
  }
}

/// Collects what a frame source delivers, on its delegate queue.
final class RecordingFrameObserver: ARFrameObserver {
  enum Event: Equatable {
    case start(epoch: Int)
    case tracking(String)
    case frame
    case stop
  }

  private let lock = NSLock()
  private var collected: [Event] = []
  private var collectedFrames: [CaptureFrame] = []
  private var collectedAnchorUpdates: [[ARAnchor]] = []
  /// Called on the delegate queue after every event.
  var onEvent: (() -> Void)?

  var events: [Event] { lock.lock(); defer { lock.unlock() }; return collected }
  var frames: [CaptureFrame] { lock.lock(); defer { lock.unlock() }; return collectedFrames }
  /// Every `didUpdateAnchors` report in order (not part of [events]).
  var anchorUpdates: [[ARAnchor]] { lock.lock(); defer { lock.unlock() }; return collectedAnchorUpdates }

  private func append(_ event: Event, frame: CaptureFrame? = nil) {
    lock.lock()
    collected.append(event)
    if let frame = frame { collectedFrames.append(frame) }
    lock.unlock()
    onEvent?()
  }

  func frameSource(_ source: ARFrameSource, didUpdate frame: CaptureFrame) { append(.frame, frame: frame) }
  func frameSource(_ source: ARFrameSource, didUpdateAnchors anchors: [ARAnchor]) {
    lock.lock()
    collectedAnchorUpdates.append(anchors)
    lock.unlock()
  }
  func frameSource(_ source: ARFrameSource, didStart configuration: CaptureRunConfiguration, epoch: Int) {
    append(.start(epoch: epoch))
  }
  func frameSource(_ source: ARFrameSource, trackingChanged state: ARCamera.TrackingState) {
    append(.tracking(Self.name(state)))
  }
  func frameSourceDidStop(_ source: ARFrameSource) { append(.stop) }

  /// `normal`, `notAvailable` or `limited(<reason>)`.
  static func name(_ state: ARCamera.TrackingState) -> String {
    switch state {
    case .normal: return "normal"
    case .notAvailable: return "notAvailable"
    case .limited(let reason):
      switch reason {
      case .initializing: return "limited(initializing)"
      case .excessiveMotion: return "limited(excessiveMotion)"
      case .insufficientFeatures: return "limited(insufficientFeatures)"
      case .relocalizing: return "limited(relocalizing)"
      @unknown default: return "limited(unknown)"
      }
    }
  }
}
