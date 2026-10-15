import ARKit
import Compression
import Foundation
import simd

/// Why a recorded session cannot be opened or read.
enum RecordedSessionError: Error, Equatable {
  case missingFile(String)
  case malformed(String)
  case unsupportedFormat(String)
  case noStill
  case noWorldMap
}

/// One 256-byte `frames.bin` record of the ARKit session recorder
/// (`benchmarks/visibility_grid/ios/Runner/Bench/ARKitSessionRecorder.swift`),
/// little-endian:
///
/// | Offset | Field |
/// |---|---|
/// | 0 | magic `C3AF` (u32 0x46413343) |
/// | 4 | version u16 (1), flags u16 (1 video, 2 depth, 4 points, 8 still) |
/// | 8 | `ARFrame.timestamp` f64 seconds |
/// | 16 | frame index u32 |
/// | 20 | tracking state u8 (0 not available, 1 limited, 2 normal), reason u8 (1 initializing, 2 excessive motion, 3 insufficient features, 4 relocalizing), 2 pad bytes |
/// | 24 | camera-to-world 16 f32, column-major |
/// | 88 | intrinsics 9 f32, column-major |
/// | 124 | image width, height u32 |
/// | 132 | exposure duration f32 s, exposure offset f32 |
/// | 140 | ambient intensity f32, colour temperature f32 (-1 without light estimate) |
/// | 148 | video, depth, points, still index u32 (`0xFFFFFFFF` none) |
/// | 164 | world mapping status u8 |
struct RecordedFrameRecord: Equatable {
  static let bytes = 256
  static let magic: UInt32 = 0x4641_3343
  static let noIndex = UInt32.max

  var timestamp: TimeInterval
  var frameIndex: UInt32
  var trackingStateCode: UInt8
  var trackingReasonCode: UInt8
  var cameraTransform: simd_float4x4
  var intrinsics: simd_float3x3
  var imageWidth: Int
  var imageHeight: Int
  var exposureDuration: Float
  var exposureOffset: Float
  var ambientIntensity: Float
  var ambientColorTemperature: Float
  var videoIndex: UInt32?
  var depthIndex: UInt32?
  var pointsIndex: UInt32?
  var stillIndex: UInt32?
  var worldMappingStatus: UInt8

  init(parsing raw: UnsafeRawBufferPointer) throws {
    guard raw.count >= Self.bytes else { throw RecordedSessionError.malformed("frame record of \(raw.count) bytes") }
    guard raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self) == Self.magic else {
      throw RecordedSessionError.malformed("frame record magic")
    }
    let version = raw.loadUnaligned(fromByteOffset: 4, as: UInt16.self)
    guard version == 1 else { throw RecordedSessionError.unsupportedFormat("frame record version \(version)") }
    func f32(_ offset: Int) -> Float { raw.loadUnaligned(fromByteOffset: offset, as: Float.self) }
    func u32(_ offset: Int) -> UInt32 { raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self) }
    func index(_ offset: Int) -> UInt32? {
      let value = u32(offset)
      return value == Self.noIndex ? nil : value
    }
    timestamp = raw.loadUnaligned(fromByteOffset: 8, as: Double.self)
    frameIndex = u32(16)
    trackingStateCode = raw[20]
    trackingReasonCode = raw[21]
    var transform = simd_float4x4()
    for c in 0..<4 { for r in 0..<4 { transform[c][r] = f32(24 + (c * 4 + r) * 4) } }
    cameraTransform = transform
    var k = simd_float3x3()
    for c in 0..<3 { for r in 0..<3 { k[c][r] = f32(88 + (c * 3 + r) * 4) } }
    intrinsics = k
    imageWidth = Int(u32(124))
    imageHeight = Int(u32(128))
    exposureDuration = f32(132)
    exposureOffset = f32(136)
    ambientIntensity = f32(140)
    ambientColorTemperature = f32(144)
    videoIndex = index(148)
    depthIndex = index(152)
    pointsIndex = index(156)
    stillIndex = index(160)
    worldMappingStatus = raw[164]
  }

  /// The recorded `ARCamera.TrackingState`. An unknown reason code replays
  /// as insufficient features, which never starts a new epoch.
  var trackingState: ARCamera.TrackingState {
    switch trackingStateCode {
    case 2: return .normal
    case 1:
      switch trackingReasonCode {
      case 1: return .limited(.initializing)
      case 2: return .limited(.excessiveMotion)
      case 3: return .limited(.insufficientFeatures)
      case 4: return .limited(.relocalizing)
      default: return .limited(.insufficientFeatures)
      }
    default: return .notAvailable
    }
  }

  /// True when ARKit was (re)initialising the world frame at this frame.
  var isInitializing: Bool { trackingStateCode == 1 && trackingReasonCode == 1 }

  var lightEstimate: CaptureLightEstimate? {
    guard ambientIntensity >= 0 else { return nil }
    return CaptureLightEstimate(
      ambientIntensity: Double(ambientIntensity), ambientColorTemperature: Double(ambientColorTemperature))
  }
}

/// One decoded `depth.bin` map: u16 millimetres (0 = no depth) and the u8
/// ARKit confidence level per pixel, both row-major `width * height`.
struct RecordedDepthMap: Equatable {
  var index: UInt32
  var timestamp: TimeInterval
  var width: Int
  var height: Int
  var millimetres: [UInt16]
  var confidence: [UInt8]
}

/// One `stills.jsonl` line: a `captureHighResolutionFrame` still with its
/// own frame's pose and intrinsics.
struct RecordedStill: Equatable {
  var index: Int
  /// Path relative to the recording (`stills/0000.jpg`).
  var file: String
  var requestTimestamp: TimeInterval
  var timestamp: TimeInterval
  var width: Int
  var height: Int
  var cameraTransform: simd_float4x4
  var intrinsics: simd_float3x3

  init(json: [String: Any]) throws {
    guard let index = json["index"] as? Int, let file = json["file"] as? String,
          let timestamp = json["timestamp"] as? Double,
          let width = json["width"] as? Int, let height = json["height"] as? Int,
          let transform = json["transform"] as? [Double], transform.count == 16,
          let intrinsics = json["intrinsics"] as? [Double], intrinsics.count == 9
    else { throw RecordedSessionError.malformed("stills.jsonl line") }
    self.index = index
    self.file = file
    self.timestamp = timestamp
    requestTimestamp = json["requestTimestamp"] as? Double ?? timestamp
    self.width = width
    self.height = height
    var m = simd_float4x4()
    for c in 0..<4 { for r in 0..<4 { m[c][r] = Float(transform[c * 4 + r]) } }
    cameraTransform = m
    var k = simd_float3x3()
    for c in 0..<3 { for r in 0..<3 { k[c][r] = Float(intrinsics[c * 3 + r]) } }
    self.intrinsics = k
  }
}

/// A `meta.json` `videoSegments` entry: a movie file holding video indexes
/// `firstVideoIndex ..< firstVideoIndex + frames`.
struct RecordedVideoSegment: Equatable {
  var file: String
  var firstVideoIndex: UInt32
  var frames: Int

  func contains(_ videoIndex: UInt32) -> Bool {
    videoIndex >= firstVideoIndex && Int(videoIndex - firstVideoIndex) < frames
  }
}

/// `meta.json` of a recording (format `capture3d-arkit-recording`, version 1).
struct RecordedSessionMeta: Equatable {
  static let format = "capture3d-arkit-recording"

  var firstTimestamp: TimeInterval
  var lastTimestamp: TimeInterval
  var frames: Int
  var depthMaps: Int
  var pointClouds: Int
  var stills: Int
  /// Movie segments that can be read; empty when the recording has no
  /// usable video (an early recorder wrote one `video.mov` without a
  /// segment list).
  var videoSegments: [RecordedVideoSegment]
  var worldMapFile: String?
  /// Hardware model identifier of the recording iPhone (`device`, for
  /// example `iPhone13,3`); nil when the recorder did not write it.
  var deviceModel: String?
  /// Recorded camera image size (`videoSize`); nil when absent.
  var videoWidth: Int?
  var videoHeight: Int?
  /// Recorded video frame rate (`videoFps`); 0 when absent.
  var videoFramesPerSecond = 0

  init(json: [String: Any]) throws {
    guard json["format"] as? String == Self.format else {
      throw RecordedSessionError.unsupportedFormat("format \(json["format"] ?? "missing")")
    }
    guard json["version"] as? Int == 1 else {
      throw RecordedSessionError.unsupportedFormat("version \(json["version"] ?? "missing")")
    }
    let recordBytes = json["frameRecordBytes"] as? Int ?? RecordedFrameRecord.bytes
    guard recordBytes == RecordedFrameRecord.bytes else {
      throw RecordedSessionError.unsupportedFormat("frame record of \(recordBytes) bytes")
    }
    firstTimestamp = json["firstTimestamp"] as? Double ?? 0
    lastTimestamp = json["lastTimestamp"] as? Double ?? 0
    frames = json["frames"] as? Int ?? 0
    depthMaps = json["depthMaps"] as? Int ?? 0
    pointClouds = json["pointClouds"] as? Int ?? 0
    stills = json["stills"] as? Int ?? 0
    let segments = json["videoSegments"] as? [[String: Any]] ?? []
    videoSegments = segments.compactMap { entry in
      guard let file = entry["file"] as? String, let first = entry["firstVideoIndex"] as? Int,
            let frames = entry["frames"] as? Int, first >= 0, frames > 0 else { return nil }
      return RecordedVideoSegment(file: file, firstVideoIndex: UInt32(first), frames: frames)
    }
    worldMapFile = (json["worldMap"] as? [String: Any])?["file"] as? String
    if let device = (json["device"] as? String)?.trimmingCharacters(in: .whitespaces), !device.isEmpty {
      deviceModel = device
    }
    if let size = json["videoSize"] as? [Int], size.count == 2, size[0] > 0, size[1] > 0 {
      videoWidth = size[0]
      videoHeight = size[1]
    }
    videoFramesPerSecond = max(0, json["videoFps"] as? Int ?? 0)
  }
}

/// A recording folder of the ARKit session recorder, opened read-only:
/// `meta.json`, `frames.bin`, `depth.bin`, `points.bin`, `stills/` with
/// `stills.jsonl`, `video_NNN.mov` and `worldmap.arworldmap`. The binary
/// files are memory-mapped; depth and point chunks are indexed once when
/// the session opens. Thread-safe for reads.
final class RecordedSession {
  static let depthChunkMagic: UInt32 = 0x4441_3343
  static let depthChunkHeaderBytes = 32
  static let pointsChunkMagic: UInt32 = 0x5041_3343
  static let pointsChunkHeaderBytes = 24
  static let pointBytes = 20

  let directory: URL
  let meta: RecordedSessionMeta
  /// Stills sorted by their exposure timestamp.
  let stills: [RecordedStill]
  /// Video segments whose movie file exists.
  let videoSegments: [RecordedVideoSegment]

  private let frames: Data
  private let depth: Data
  private let points: Data
  private let depthChunks: [Int]
  private let pointChunks: [Int]

  init(directory: URL) throws {
    self.directory = directory
    let fileManager = FileManager.default
    func url(_ name: String) -> URL { directory.appendingPathComponent(name) }
    func mapped(_ name: String, required: Bool) throws -> Data {
      let file = url(name)
      guard fileManager.fileExists(atPath: file.path) else {
        if required { throw RecordedSessionError.missingFile(name) }
        return Data()
      }
      return try Data(contentsOf: file, options: .alwaysMapped)
    }
    guard let metaData = try? Data(contentsOf: url("meta.json")) else {
      throw RecordedSessionError.missingFile("meta.json")
    }
    guard let metaJson = try JSONSerialization.jsonObject(with: metaData) as? [String: Any] else {
      throw RecordedSessionError.malformed("meta.json")
    }
    meta = try RecordedSessionMeta(json: metaJson)
    frames = try mapped("frames.bin", required: true)
    guard frames.count >= RecordedFrameRecord.bytes else { throw RecordedSessionError.malformed("frames.bin is empty") }
    depth = try mapped("depth.bin", required: false)
    points = try mapped("points.bin", required: false)
    depthChunks = try Self.indexChunks(depth, magic: Self.depthChunkMagic, header: Self.depthChunkHeaderBytes, name: "depth.bin") { raw, offset in
      Self.depthChunkHeaderBytes + Int(raw.loadUnaligned(fromByteOffset: offset + 20, as: UInt32.self))
    }
    pointChunks = try Self.indexChunks(points, magic: Self.pointsChunkMagic, header: Self.pointsChunkHeaderBytes, name: "points.bin") { raw, offset in
      Self.pointsChunkHeaderBytes
        + Int(raw.loadUnaligned(fromByteOffset: offset + 16, as: UInt32.self)) * Self.pointBytes
    }
    var stills: [RecordedStill] = []
    if let log = try? String(contentsOf: url("stills.jsonl"), encoding: .utf8) {
      for line in log.split(separator: "\n") where !line.isEmpty {
        guard let json = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
              let still = try? RecordedStill(json: json),
              fileManager.fileExists(atPath: url(still.file).path) else { continue }
        stills.append(still)
      }
    }
    self.stills = stills.sorted { $0.timestamp < $1.timestamp }
    videoSegments = meta.videoSegments.filter { fileManager.fileExists(atPath: url($0.file).path) }
  }

  var frameCount: Int { frames.count / RecordedFrameRecord.bytes }
  var depthMapCount: Int { depthChunks.count }
  var pointCloudCount: Int { pointChunks.count }
  var hasVideo: Bool { !videoSegments.isEmpty }

  func frame(at index: Int) throws -> RecordedFrameRecord {
    guard index >= 0, index < frameCount else { throw RecordedSessionError.malformed("frame \(index) out of range") }
    return try frames.withUnsafeBytes { raw in
      try RecordedFrameRecord(parsing: UnsafeRawBufferPointer(
        rebasing: raw[(index * RecordedFrameRecord.bytes)..<((index + 1) * RecordedFrameRecord.bytes)]))
    }
  }

  /// Decodes depth chunk [index]: LZFSE (encoding 1) or stored (0) bytes of
  /// u16 millimetres followed by u8 confidence.
  func depthMap(_ index: UInt32) throws -> RecordedDepthMap {
    guard Int(index) < depthChunks.count else { throw RecordedSessionError.malformed("depth \(index) out of range") }
    let offset = depthChunks[Int(index)]
    return try depth.withUnsafeBytes { raw in
      let chunkIndex = raw.loadUnaligned(fromByteOffset: offset + 4, as: UInt32.self)
      let timestamp = raw.loadUnaligned(fromByteOffset: offset + 8, as: Double.self)
      let width = Int(raw.loadUnaligned(fromByteOffset: offset + 16, as: UInt16.self))
      let height = Int(raw.loadUnaligned(fromByteOffset: offset + 18, as: UInt16.self))
      let compressedSize = Int(raw.loadUnaligned(fromByteOffset: offset + 20, as: UInt32.self))
      let rawSize = Int(raw.loadUnaligned(fromByteOffset: offset + 24, as: UInt32.self))
      let encoding = raw.loadUnaligned(fromByteOffset: offset + 28, as: UInt32.self)
      let pixels = width * height
      guard pixels > 0, rawSize == pixels * 3 else { throw RecordedSessionError.malformed("depth \(index) size") }
      let payload = UnsafeRawBufferPointer(
        rebasing: raw[(offset + Self.depthChunkHeaderBytes)..<(offset + Self.depthChunkHeaderBytes + compressedSize)])
      var decoded = [UInt8](repeating: 0, count: rawSize)
      switch encoding {
      case 0:
        guard compressedSize == rawSize else { throw RecordedSessionError.malformed("depth \(index) stored size") }
        decoded.withUnsafeMutableBytes { $0.copyMemory(from: payload) }
      case 1:
        let written = decoded.withUnsafeMutableBufferPointer { out in
          compression_decode_buffer(
            out.baseAddress!, rawSize, payload.bindMemory(to: UInt8.self).baseAddress!, compressedSize, nil,
            COMPRESSION_LZFSE)
        }
        guard written == rawSize else { throw RecordedSessionError.malformed("depth \(index) LZFSE") }
      default:
        throw RecordedSessionError.unsupportedFormat("depth encoding \(encoding)")
      }
      let millimetres = decoded.withUnsafeBytes { bytes in
        (0..<pixels).map { bytes.loadUnaligned(fromByteOffset: $0 * 2, as: UInt16.self) }
      }
      return RecordedDepthMap(
        index: chunkIndex, timestamp: timestamp, width: width, height: height,
        millimetres: millimetres, confidence: Array(decoded[(pixels * 2)...]))
    }
  }

  /// Raw feature points of chunk [index] (world space, identifiers).
  func featurePoints(_ index: UInt32) throws -> CaptureFeaturePoints {
    guard Int(index) < pointChunks.count else { throw RecordedSessionError.malformed("points \(index) out of range") }
    let offset = pointChunks[Int(index)]
    return points.withUnsafeBytes { raw in
      let count = Int(raw.loadUnaligned(fromByteOffset: offset + 16, as: UInt32.self))
      var positions: [simd_float3] = []
      var identifiers: [UInt64] = []
      positions.reserveCapacity(count)
      identifiers.reserveCapacity(count)
      var at = offset + Self.pointsChunkHeaderBytes
      for _ in 0..<count {
        positions.append(simd_float3(
          raw.loadUnaligned(fromByteOffset: at, as: Float.self),
          raw.loadUnaligned(fromByteOffset: at + 4, as: Float.self),
          raw.loadUnaligned(fromByteOffset: at + 8, as: Float.self)))
        identifiers.append(raw.loadUnaligned(fromByteOffset: at + 12, as: UInt64.self))
        at += Self.pointBytes
      }
      return CaptureFeaturePoints(points: positions, identifiers: identifiers)
    }
  }

  /// The still whose exposure is nearest to [recordedTimestamp] (recording
  /// clock); nil without stills.
  func stillNearest(to recordedTimestamp: TimeInterval) -> RecordedStill? {
    guard !stills.isEmpty else { return nil }
    var low = 0
    var high = stills.count
    while low < high {
      let mid = (low + high) / 2
      if stills[mid].timestamp < recordedTimestamp { low = mid + 1 } else { high = mid }
    }
    if low == 0 { return stills[0] }
    if low == stills.count { return stills[stills.count - 1] }
    let before = stills[low - 1]
    let after = stills[low]
    return recordedTimestamp - before.timestamp <= after.timestamp - recordedTimestamp ? before : after
  }

  func url(of still: RecordedStill) -> URL { directory.appendingPathComponent(still.file) }

  /// `worldmap.arworldmap` when the recording saved one.
  var worldMapURL: URL? {
    guard let file = meta.worldMapFile else { return nil }
    let url = directory.appendingPathComponent(file)
    return FileManager.default.fileExists(atPath: url.path) ? url : nil
  }

  /// Offsets of the chunks of [data]; a truncated tail chunk (recorder
  /// stopped mid-write) is dropped.
  private static func indexChunks(
    _ data: Data, magic: UInt32, header: Int, name: String, size: (UnsafeRawBufferPointer, Int) -> Int
  ) throws -> [Int] {
    try data.withUnsafeBytes { raw in
      var offsets: [Int] = []
      var offset = 0
      while offset + header <= raw.count {
        guard raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self) == magic else {
          throw RecordedSessionError.malformed("\(name) chunk magic at \(offset)")
        }
        let length = size(raw, offset)
        guard offset + length <= raw.count else { break }
        offsets.append(offset)
        offset += length
      }
      return offsets
    }
  }
}
