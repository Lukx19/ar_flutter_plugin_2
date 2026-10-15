import Foundation
import simd

/// Kind 5 marker detections payload (contract 2.4 and 2.8): an 8 B prefix
/// (u16 count, u16 image width, u16 image height, u16 reserved) and 64 B
/// records.
enum MarkerDetectionLayout {
  static let prefixBytes = 8
  static let recordBytes = 64
  /// Records per message, as on Android.
  static let maxRecords = 64
}

/// Header fields of one marker detection message besides the records.
struct MarkerDetectionHeader {
  var sampleNs: Int64
  var acquireNs: Int64
  var trackingState: Int
  var poseMatch: Int
  var groupEpoch: Int
  var cameraToWorld: simd_float4x4
  /// fx, fy, cx, cy for the grey image the corners are in.
  var intrinsics: simd_float4
  var imageWidth: Int
  var imageHeight: Int
  /// Images skipped since the last message (detector busy).
  var dropped: UInt32
  /// Frame time when it differs from the sample (nil: the sample time).
  var frameNs: Int64? = nil
  var poseGapUs: Int32 = 0
}

/// Writes ingest kind 5 messages into one buffer allocated once, the Swift
/// twin of the Android `MarkerDetectionMessageWriter`: header with the pose
/// at the grey image timestamp, prefix, then the native records copied as
/// they are. Sequence and send time are stamped by the ingest sink.
final class MarkerDetectionMessageWriter {
  static let recordsOffset = IngestMessageLayout.headerBytes + MarkerDetectionLayout.prefixBytes
  let buffer: IngestMessageBuffer
  let maxRecords: Int

  init(maxRecords: Int = MarkerDetectionLayout.maxRecords) {
    self.maxRecords = maxRecords
    buffer = IngestMessageBuffer(capacity: Self.recordsOffset + maxRecords * MarkerDetectionLayout.recordBytes)
  }

  /// Writes a message of the first min(count, maxRecords) records at
  /// [records]; returns its length.
  func write(_ header: MarkerDetectionHeader, records: UnsafeRawPointer?, count: Int) -> Int {
    let n = max(0, min(count, maxRecords))
    let b = buffer
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindMarkerDetections)
    IngestMessageWriter.payloadBytes(b, MarkerDetectionLayout.prefixBytes + n * MarkerDetectionLayout.recordBytes)
    IngestMessageWriter.timing(
      b, sampleNs: header.sampleNs, frameNs: header.frameNs ?? header.sampleNs, acquireNs: header.acquireNs)
    IngestMessageWriter.pose(
      b, trackingState: header.trackingState, poseMatch: header.poseMatch, groupEpoch: header.groupEpoch,
      poseGapUs: header.poseGapUs, cameraToWorld: header.cameraToWorld)
    let k = header.intrinsics
    IngestMessageWriter.intrinsics(b, fx: k.x, fy: k.y, cx: k.z, cy: k.w)
    b.u16(IngestMessageLayout.width, header.imageWidth)
    b.u16(IngestMessageLayout.height, header.imageHeight)
    IngestMessageWriter.dropped(b, header.dropped)
    let p = IngestMessageLayout.headerBytes
    b.u16(p, n)
    b.u16(p + 2, header.imageWidth)
    b.u16(p + 4, header.imageHeight)
    b.u16(p + 6, 0)
    if n > 0, let source = records {
      (b.bytes + Self.recordsOffset).copyMemory(from: source, byteCount: n * MarkerDetectionLayout.recordBytes)
    }
    return Self.recordsOffset + n * MarkerDetectionLayout.recordBytes
  }
}

/// 64 B header of a photo tie point batch (`C3TB`), followed by 64 B native
/// tie point records; mirrors the Android `TiePointBatchLayout` and
/// `TiePointBatchLayout` in `capture3d/lib/capture/visibility_grid/markers/tie_points.dart`.
enum TiePointBatchLayout {
  static let magic = contractMagic("C3TB")
  static let version = 1
  static let headerBytes = 64
  static let count = 6
  static let photoId = 8
  static let photoIdBytes = 32
  static let width = 40
  static let height = 42
  static let groupEpoch = 44
  static let status = 46
  static let exposureTimestampNs = 48
  static let timeMs = 56
}

/// Writes ingest kind 8 messages (one per saved photo): header with the
/// exposure as sample and frame time, pose missing and zero, the photo's
/// group epoch; payload the `C3TB` batch header and the native records.
final class TiePointMessageWriter {
  let buffer: IngestMessageBuffer
  let maxRecords: Int

  init(maxRecords: Int = 128) {
    self.maxRecords = maxRecords
    buffer = IngestMessageBuffer(
      capacity: IngestMessageLayout.headerBytes + TiePointBatchLayout.headerBytes + maxRecords * 64)
  }

  /// [status] is the native status (0 or a negative C3MD error), stored negated.
  func write(
    photoId: [UInt8], width: Int, height: Int, groupEpoch: Int, exposureNs: Int64, status: Int32, timeMs: Float,
    acquireNs: Int64, records: UnsafeRawPointer?, count: Int
  ) -> Int {
    let n = max(0, min(count, maxRecords))
    let b = buffer
    let batchBytes = TiePointBatchLayout.headerBytes + n * 64
    IngestMessageWriter.begin(b, kind: IngestMessageLayout.kindPhotoTiePoints)
    IngestMessageWriter.timing(b, sampleNs: exposureNs, frameNs: exposureNs, acquireNs: acquireNs)
    b.u8(IngestMessageLayout.poseMatch, IngestMessageLayout.poseMatchMissing)
    b.u16(IngestMessageLayout.groupEpoch, groupEpoch)
    IngestMessageWriter.payloadBytes(b, batchBytes)
    let o = IngestMessageLayout.headerBytes
    b.zero(o, TiePointBatchLayout.headerBytes)
    b.u32(o, TiePointBatchLayout.magic)
    b.u16(o + 4, TiePointBatchLayout.version)
    b.u16(o + TiePointBatchLayout.count, n)
    for i in 0..<min(TiePointBatchLayout.photoIdBytes, photoId.count) {
      b.u8(o + TiePointBatchLayout.photoId + i, Int(photoId[i]))
    }
    b.u16(o + TiePointBatchLayout.width, width)
    b.u16(o + TiePointBatchLayout.height, height)
    b.u16(o + TiePointBatchLayout.groupEpoch, groupEpoch)
    b.u16(o + TiePointBatchLayout.status, Int(-status))
    b.i64(o + TiePointBatchLayout.exposureTimestampNs, exposureNs)
    b.f32(o + TiePointBatchLayout.timeMs, timeMs)
    if n > 0, let source = records {
      (b.bytes + o + TiePointBatchLayout.headerBytes).copyMemory(from: source, byteCount: n * 64)
    }
    return o + batchBytes
  }
}
