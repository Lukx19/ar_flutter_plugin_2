import 'dart:typed_data';

import 'binary_support.dart';

/// Ingest message kinds (header byte 6).
abstract final class IngestKind {
  static const int idle = 0;
  static const int depthImage = 1;
  static const int pointCloud = 2;
  static const int aggregatedVoxels = 3;
  static const int photoSaved = 4;
  static const int markerDetections = 5;
  static const int sessionEvent = 6;

  /// Anchor pose update of pose stability (plan 2.14): ingest header plus
  /// the anchor pose payload. Travels on the pose tracking channel, not
  /// through the ingest pull, so it is never part of a pull kind mask.
  static const int anchorPoseUpdate = 7;

  /// Marker tie points of one saved photo, after its durable commit:
  /// payload is the 64 B `C3TB` batch header and 64 B native tie point
  /// records (decoded by the marker stage); header time and pose fields are
  /// zero, the group epoch is the photo's. Queued in order, never dropped
  /// for a fresher one. Contract amendment of the grid integration.
  static const int photoTiePoints = 8;
  static const int count = 9;
}

/// Source flags (header byte 7). Bit 0 depth, bit 1 points, bit 2
/// LiDAR-class depth, bit 3 synthetic.
abstract final class IngestSourceFlags {
  static const int depth = 1;
  static const int points = 2;
  static const int lidarClass = 4;
  static const int synthetic = 8;
}

/// How the header pose was matched to the sample timestamp (header byte 49).
/// The grid drops samples with [missing].
abstract final class PoseMatch {
  static const int exact = 0;
  static const int interpolated = 1;
  static const int nearestWithin20Ms = 2;
  static const int missing = 3;
}

/// Depth value encoding (header byte 142).
abstract final class DepthUnit {
  static const int u16Millimetres = 0;
  static const int f32Metres = 1;
}

/// Confidence encoding (header byte 143).
abstract final class ConfidenceFormat {
  static const int none = 0;
  static const int u8 = 1;
}

/// Session events (payload byte 0 of kind 6).
abstract final class SessionEventKind {
  static const int trackingLost = 0;
  static const int trackingRegained = 1;
  static const int arSessionRestarted = 2;
  static const int depthModeChanged = 3;
  static const int recordingStarted = 4;
  static const int recordingStopped = 5;

  /// ARCore tracking may have been reset: earlier samples can sit in a
  /// different world frame. Value: low byte a [TrackingResetCause], bits 8
  /// and up a cause detail (failure reason code or anchor jump in mm).
  static const int trackingReset = 6;

  /// The AR session resumed after a pause. Value: frame gap in nanoseconds.
  static const int arSessionResumed = 7;
}

/// Cause byte of a [SessionEventKind.trackingReset] value.
abstract final class TrackingResetCause {
  static const int arSessionResumed = 1;
  static const int trackingFailure = 2;
  static const int anchorJump = 3;

  static int cause(int value) => value & 0xff;
  static int detail(int value) => value >> 8;
}

/// Byte layout of the 160 B ingest message header. Offsets are normative and
/// mirrored by `IngestMessageLayout.kt` and `ingest_message_layout.h`.
abstract final class IngestHeaderLayout {
  static final int magic = contractMagic('C3VI');
  static const int version = 1;
  static const int bytes = 160;

  /// Largest whole message (header plus payload).
  static const int maxMessageBytes = 262144;

  static const int magicOffset = 0; // u32
  static const int versionOffset = 4; // u16
  static const int kindOffset = 6; // u8
  static const int sourceFlagsOffset = 7; // u8
  static const int sequenceOffset = 8; // u32
  static const int payloadBytesOffset = 12; // u32
  static const int sampleTimestampNsOffset = 16; // i64
  static const int frameTimestampNsOffset = 24; // i64
  static const int acquireTimeNsOffset = 32; // i64
  static const int sendTimeNsOffset = 40; // i64
  static const int trackingStateOffset = 48; // u8
  static const int poseMatchOffset = 49; // u8
  static const int groupEpochOffset = 50; // u16
  static const int poseGapUsOffset = 52; // i32
  static const int cameraToWorldOffset = 56; // f32 x16 column-major
  static const int intrinsicsOffset = 120; // f32 x4 fx fy cx cy
  static const int widthOffset = 136; // u16
  static const int heightOffset = 138; // u16
  static const int rowStrideOffset = 140; // u16
  static const int depthUnitOffset = 142; // u8
  static const int confidenceFormatOffset = 143; // u8
  static const int droppedOffset = 144; // u32
  static const int reservedOffset = 148; // u32 x3
}

/// Reusable view over an ingest message header and its payload.
final class IngestMessageView extends ContractRecordView {
  @override
  int get recordBytes => IngestHeaderLayout.bytes;

  /// Byte offset of the payload in [data].
  int get payloadOffset => offset + IngestHeaderLayout.bytes;

  /// Validates magic, version, kind and declared payload length against
  /// [available] bytes from [offset].
  ContractStatus validate(int available) {
    final status = validateMagicAndVersion(
      data,
      offset,
      available,
      IngestHeaderLayout.bytes,
      IngestHeaderLayout.magic,
      IngestHeaderLayout.version,
    );
    if (status != ContractStatus.ok) return status;
    if (kind >= IngestKind.count) return ContractStatus.badKind;
    final total = IngestHeaderLayout.bytes + payloadBytes;
    if (total > IngestHeaderLayout.maxMessageBytes) {
      return ContractStatus.tooLarge;
    }
    if (total > available) return ContractStatus.badLength;
    return ContractStatus.ok;
  }

  /// Writes magic and version and zeroes every other header field.
  void begin(int kind) {
    clear();
    setU32(IngestHeaderLayout.magicOffset, IngestHeaderLayout.magic);
    setU16(IngestHeaderLayout.versionOffset, IngestHeaderLayout.version);
    this.kind = kind;
  }

  int get kind => u8(IngestHeaderLayout.kindOffset);
  set kind(int v) => setU8(IngestHeaderLayout.kindOffset, v);
  int get sourceFlags => u8(IngestHeaderLayout.sourceFlagsOffset);
  set sourceFlags(int v) => setU8(IngestHeaderLayout.sourceFlagsOffset, v);
  int get sequence => u32(IngestHeaderLayout.sequenceOffset);
  set sequence(int v) => setU32(IngestHeaderLayout.sequenceOffset, v);
  int get payloadBytes => u32(IngestHeaderLayout.payloadBytesOffset);
  set payloadBytes(int v) => setU32(IngestHeaderLayout.payloadBytesOffset, v);
  int get sampleTimestampNs => i64(IngestHeaderLayout.sampleTimestampNsOffset);
  set sampleTimestampNs(int v) =>
      setI64(IngestHeaderLayout.sampleTimestampNsOffset, v);
  int get frameTimestampNs => i64(IngestHeaderLayout.frameTimestampNsOffset);
  set frameTimestampNs(int v) =>
      setI64(IngestHeaderLayout.frameTimestampNsOffset, v);
  int get acquireTimeNs => i64(IngestHeaderLayout.acquireTimeNsOffset);
  set acquireTimeNs(int v) => setI64(IngestHeaderLayout.acquireTimeNsOffset, v);
  int get sendTimeNs => i64(IngestHeaderLayout.sendTimeNsOffset);
  set sendTimeNs(int v) => setI64(IngestHeaderLayout.sendTimeNsOffset, v);
  int get trackingState => u8(IngestHeaderLayout.trackingStateOffset);
  set trackingState(int v) => setU8(IngestHeaderLayout.trackingStateOffset, v);
  int get poseMatch => u8(IngestHeaderLayout.poseMatchOffset);
  set poseMatch(int v) => setU8(IngestHeaderLayout.poseMatchOffset, v);
  int get groupEpoch => u16(IngestHeaderLayout.groupEpochOffset);
  set groupEpoch(int v) => setU16(IngestHeaderLayout.groupEpochOffset, v);
  int get poseGapUs => i32(IngestHeaderLayout.poseGapUsOffset);
  set poseGapUs(int v) => setI32(IngestHeaderLayout.poseGapUsOffset, v);

  /// Camera-to-world element [index] (column-major) at the sample timestamp.
  double cameraToWorld(int index) =>
      f32(IngestHeaderLayout.cameraToWorldOffset + 4 * index);
  void readCameraToWorld(List<double> out, [int outOffset = 0]) =>
      readF32s(IngestHeaderLayout.cameraToWorldOffset, 16, out, outOffset);
  void writeCameraToWorld(List<double> values, [int valuesOffset = 0]) =>
      writeF32s(
          IngestHeaderLayout.cameraToWorldOffset, 16, values, valuesOffset);

  double get fx => f32(IngestHeaderLayout.intrinsicsOffset);
  double get fy => f32(IngestHeaderLayout.intrinsicsOffset + 4);
  double get cx => f32(IngestHeaderLayout.intrinsicsOffset + 8);
  double get cy => f32(IngestHeaderLayout.intrinsicsOffset + 12);
  void setIntrinsics(double fx, double fy, double cx, double cy) {
    setF32(IngestHeaderLayout.intrinsicsOffset, fx);
    setF32(IngestHeaderLayout.intrinsicsOffset + 4, fy);
    setF32(IngestHeaderLayout.intrinsicsOffset + 8, cx);
    setF32(IngestHeaderLayout.intrinsicsOffset + 12, cy);
  }

  int get width => u16(IngestHeaderLayout.widthOffset);
  set width(int v) => setU16(IngestHeaderLayout.widthOffset, v);
  int get height => u16(IngestHeaderLayout.heightOffset);
  set height(int v) => setU16(IngestHeaderLayout.heightOffset, v);
  int get rowStrideBytes => u16(IngestHeaderLayout.rowStrideOffset);
  set rowStrideBytes(int v) => setU16(IngestHeaderLayout.rowStrideOffset, v);
  int get depthUnit => u8(IngestHeaderLayout.depthUnitOffset);
  set depthUnit(int v) => setU8(IngestHeaderLayout.depthUnitOffset, v);
  int get confidenceFormat => u8(IngestHeaderLayout.confidenceFormatOffset);
  set confidenceFormat(int v) =>
      setU8(IngestHeaderLayout.confidenceFormatOffset, v);
  int get dropped => u32(IngestHeaderLayout.droppedOffset);
  set dropped(int v) => setU32(IngestHeaderLayout.droppedOffset, v);

  /// True when the sample carries a usable pose (not [PoseMatch.missing]).
  bool get hasMatchedPose => poseMatch != PoseMatch.missing;
}

/// Kind 1 depth image payload: `height` rows of `rowStrideBytes` depth bytes,
/// then `width * height` u8 confidence values when the confidence format is
/// [ConfidenceFormat.u8].
abstract final class DepthImagePayload {
  static int depthBytes(IngestMessageView header) =>
      header.height * header.rowStrideBytes;

  static int confidenceBytes(IngestMessageView header) =>
      header.confidenceFormat == ConfidenceFormat.u8
          ? header.width * header.height
          : 0;

  static int payloadBytes(IngestMessageView header) =>
      depthBytes(header) + confidenceBytes(header);

  /// Depth at pixel ([x], [y]) in metres; 0 means no depth.
  static double depthMetres(IngestMessageView header, int x, int y) {
    final at = header.payloadOffset + y * header.rowStrideBytes;
    if (header.depthUnit == DepthUnit.u16Millimetres) {
      return header.data.getUint16(at + 2 * x, contractEndian) / 1000.0;
    }
    return header.data.getFloat32(at + 4 * x, contractEndian);
  }

  /// Confidence 0-255 at pixel ([x], [y]); 255 when no confidence is sent.
  static int confidence(IngestMessageView header, int x, int y) {
    if (header.confidenceFormat != ConfidenceFormat.u8) return 255;
    return header.data.getUint8(
        header.payloadOffset + depthBytes(header) + y * header.width + x);
  }
}

/// Kind 2 point cloud payload: u32 count, then 20 B per point (id u32,
/// x y z f32, confidence f32). Non-finite points are dropped on the platform.
abstract final class PointCloudPayload {
  static const int countBytes = 4;
  static const int pointBytes = 20;
  static const int idOffset = 0;
  static const int positionOffset = 4;
  static const int confidenceOffset = 16;

  static int payloadBytes(int count) => countBytes + count * pointBytes;

  static int count(IngestMessageView header) =>
      header.data.getUint32(header.payloadOffset, contractEndian);

  static void setCount(IngestMessageView header, int count) =>
      header.data.setUint32(header.payloadOffset, count, contractEndian);

  static void writePoint(IngestMessageView header, int index, int id, double x,
      double y, double z, double confidence) {
    final at = header.payloadOffset + countBytes + index * pointBytes;
    final d = header.data;
    d.setUint32(at + idOffset, id, contractEndian);
    d.setFloat32(at + positionOffset, x, contractEndian);
    d.setFloat32(at + positionOffset + 4, y, contractEndian);
    d.setFloat32(at + positionOffset + 8, z, contractEndian);
    d.setFloat32(at + confidenceOffset, confidence, contractEndian);
  }

  /// Decodes up to the capacity of the caller's columns into feature point
  /// columns (`xyz` holds three floats per point). Returns the decoded count.
  static int readColumns(IngestMessageView header, Uint32List ids,
      Float32List xyz, Float32List confidence) {
    var n = count(header);
    if (n > ids.length) n = ids.length;
    if (n * 3 > xyz.length) n = xyz.length ~/ 3;
    if (n > confidence.length) n = confidence.length;
    final d = header.data;
    var at = header.payloadOffset + countBytes;
    for (var i = 0; i < n; i++, at += pointBytes) {
      ids[i] = d.getUint32(at + idOffset, contractEndian);
      xyz[3 * i] = d.getFloat32(at + positionOffset, contractEndian);
      xyz[3 * i + 1] = d.getFloat32(at + positionOffset + 4, contractEndian);
      xyz[3 * i + 2] = d.getFloat32(at + positionOffset + 8, contractEndian);
      confidence[i] = d.getFloat32(at + confidenceOffset, contractEndian);
    }
    return n;
  }
}

/// Kind 3 aggregated voxels payload: u32 count, then 28 B per voxel
/// (position sum f32 x3, normal sum f32 x3, sample count u32).
abstract final class AggregatedVoxelPayload {
  static const int countBytes = 4;
  static const int voxelBytes = 28;
  static const int positionSumOffset = 0;
  static const int normalSumOffset = 12;
  static const int sampleCountOffset = 24;

  static int payloadBytes(int count) => countBytes + count * voxelBytes;

  static int count(IngestMessageView header) =>
      header.data.getUint32(header.payloadOffset, contractEndian);

  static void setCount(IngestMessageView header, int count) =>
      header.data.setUint32(header.payloadOffset, count, contractEndian);

  static void writeVoxel(IngestMessageView header, int index,
      List<double> positionSum, List<double> normalSum, int samples) {
    final at = header.payloadOffset + countBytes + index * voxelBytes;
    final d = header.data;
    for (var k = 0; k < 3; k++) {
      d.setFloat32(
          at + positionSumOffset + 4 * k, positionSum[k], contractEndian);
      d.setFloat32(at + normalSumOffset + 4 * k, normalSum[k], contractEndian);
    }
    d.setUint32(at + sampleCountOffset, samples, contractEndian);
  }

  /// Decodes into caller columns (three floats per voxel for both sums).
  static int readColumns(IngestMessageView header, Float32List positionSums,
      Float32List normalSums, Uint32List sampleCounts) {
    var n = count(header);
    if (n > sampleCounts.length) n = sampleCounts.length;
    if (n * 3 > positionSums.length) n = positionSums.length ~/ 3;
    if (n * 3 > normalSums.length) n = normalSums.length ~/ 3;
    final d = header.data;
    var at = header.payloadOffset + countBytes;
    for (var i = 0; i < n; i++, at += voxelBytes) {
      for (var k = 0; k < 3; k++) {
        positionSums[3 * i + k] =
            d.getFloat32(at + positionSumOffset + 4 * k, contractEndian);
        normalSums[3 * i + k] =
            d.getFloat32(at + normalSumOffset + 4 * k, contractEndian);
      }
      sampleCounts[i] = d.getUint32(at + sampleCountOffset, contractEndian);
    }
    return n;
  }
}

/// Kind 5 marker detections payload: u16 count, u16 image width, u16 image
/// height, u16 reserved, then 64 B records (see `MarkerDetectionRecordView`).
/// The header pose is the pose at the grey image timestamp.
abstract final class MarkerDetectionsPayload {
  static const int prefixBytes = 8;
  static const int countOffset = 0;
  static const int imageWidthOffset = 2;
  static const int imageHeightOffset = 4;

  static int payloadBytes(int count) => prefixBytes + count * 64;

  static int count(IngestMessageView h) =>
      h.data.getUint16(h.payloadOffset + countOffset, contractEndian);
  static int imageWidth(IngestMessageView h) =>
      h.data.getUint16(h.payloadOffset + imageWidthOffset, contractEndian);
  static int imageHeight(IngestMessageView h) =>
      h.data.getUint16(h.payloadOffset + imageHeightOffset, contractEndian);

  static void writePrefix(
      IngestMessageView h, int count, int imageWidth, int imageHeight) {
    final d = h.data;
    final at = h.payloadOffset;
    d.setUint16(at + countOffset, count, contractEndian);
    d.setUint16(at + imageWidthOffset, imageWidth, contractEndian);
    d.setUint16(at + imageHeightOffset, imageHeight, contractEndian);
    d.setUint16(at + 6, 0, contractEndian);
  }

  /// Offset of record [index] in the message buffer.
  static int recordOffset(IngestMessageView h, int index) =>
      h.payloadOffset + prefixBytes + index * 64;
}

/// Kind 6 session event payload, 16 B: u8 event, u8 x7 reserved, i64 value.
abstract final class SessionEventPayload {
  static const int bytes = 16;
  static const int eventOffset = 0;
  static const int valueOffset = 8;

  static int event(IngestMessageView h) =>
      h.data.getUint8(h.payloadOffset + eventOffset);
  static int value(IngestMessageView h) =>
      h.data.getInt64(h.payloadOffset + valueOffset, contractEndian);

  static void write(IngestMessageView h, int event, int value) {
    final d = h.data;
    final at = h.payloadOffset;
    for (var i = 0; i < bytes; i++) {
      d.setUint8(at + i, 0);
    }
    d.setUint8(at + eventOffset, event);
    d.setInt64(at + valueOffset, value, contractEndian);
  }
}

/// 8 B pull request from the grid isolate: magic `C3VP`, u16 version, u16
/// accepted kind mask (bit k accepts kind k). Byte 7 was reserved (zero) in
/// the first contract, so an 8-bit mask reads the same; kind 8 needs the
/// high byte.
final class IngestPullRequestView extends ContractRecordView {
  static final int magic = contractMagic('C3VP');
  static const int version = 1;
  static const int bytes = 8;

  @override
  int get recordBytes => bytes;

  ContractStatus validate(int available) =>
      validateMagicAndVersion(data, offset, available, bytes, magic, version);

  void write(int acceptedKindMask) {
    setU32(0, magic);
    setU16(4, version);
    setU16(6, acceptedKindMask);
  }

  int get acceptedKindMask => u16(6);
}
