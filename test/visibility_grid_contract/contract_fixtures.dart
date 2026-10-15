import 'dart:io';
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

/// Golden fixtures shared by the Dart and Kotlin contract tests. The Kotlin
/// `VisibilityGridContractGoldenTest` asserts the same field values; change
/// both together and regenerate with `UPDATE_VISIBILITY_GRID_GOLDENS=1`.
const String goldenDirectory = 'test_assets/visibility_grid';

/// Compares [bytes] with the checked-in golden [name]; rewrites it when
/// `UPDATE_VISIBILITY_GRID_GOLDENS=1`.
void expectGoldenBytes(String name, Uint8List bytes) {
  final file = File('$goldenDirectory/$name');
  if (Platform.environment['UPDATE_VISIBILITY_GRID_GOLDENS'] == '1') {
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(bytes, flush: true);
  }
  expect(file.existsSync(), isTrue,
      reason: 'missing golden $name; regenerate with '
          'UPDATE_VISIBILITY_GRID_GOLDENS=1');
  expect(bytes, orderedEquals(file.readAsBytesSync()), reason: name);
}

/// Reads golden [name] into a fresh buffer.
ByteData readGolden(String name) =>
    ByteData.sublistView(File('$goldenDirectory/$name').readAsBytesSync());

const int fixtureSequence = 7;
const int fixtureSampleTimestampNs = 1000000000;
const int fixtureFrameTimestampNs = 1170000000;
const int fixtureAcquireTimeNs = 2000000000;
const int fixtureSendTimeNs = 2000500000;
const int fixtureGroupEpoch = 3;
const int fixturePoseGapUs = -1250;
const int fixtureDropped = 2;

/// Column-major camera-to-world: identity rotation, translation (0.5, 1.25, -2).
const List<double> fixtureCameraToWorld = [
  1, 0, 0, 0, //
  0, 1, 0, 0, //
  0, 0, 1, 0, //
  0.5, 1.25, -2, 1,
];

final IngestMessageView _header = IngestMessageView();

void _writeCommonHeader(
    IngestMessageView h, int kind, int sourceFlags, int payloadBytes) {
  h.begin(kind);
  h.sourceFlags = sourceFlags;
  h.sequence = fixtureSequence;
  h.payloadBytes = payloadBytes;
  h.sampleTimestampNs = fixtureSampleTimestampNs;
  h.frameTimestampNs = fixtureFrameTimestampNs;
  h.acquireTimeNs = fixtureAcquireTimeNs;
  h.sendTimeNs = fixtureSendTimeNs;
  h.trackingState = 1;
  h.poseMatch = PoseMatch.interpolated;
  h.groupEpoch = fixtureGroupEpoch;
  h.poseGapUs = fixturePoseGapUs;
  h.writeCameraToWorld(fixtureCameraToWorld);
  h.dropped = fixtureDropped;
}

/// Kind 1: 4 x 3 u16 millimetre depth with u8 confidence.
Uint8List buildDepthU16Message() {
  const width = 4, height = 3, stride = width * 2;
  const payload = height * stride + width * height;
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final data = ByteData.sublistView(bytes);
  final h = _header..attach(data);
  _writeCommonHeader(
      h, IngestKind.depthImage, IngestSourceFlags.depth, payload);
  h.setIntrinsics(180, 181, 80, 45);
  h.width = width;
  h.height = height;
  h.rowStrideBytes = stride;
  h.depthUnit = DepthUnit.u16Millimetres;
  h.confidenceFormat = ConfidenceFormat.u8;
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      data.setUint16(h.payloadOffset + y * stride + 2 * x,
          (y * width + x + 1) * 250, Endian.little);
      bytes[h.payloadOffset + height * stride + y * width + x] =
          (y * width + x) * 20;
    }
  }
  return bytes;
}

/// Kind 2: three feature points.
Uint8List buildPointCloudMessage() {
  final payload = PointCloudPayload.payloadBytes(3);
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final h = _header..attach(ByteData.sublistView(bytes));
  _writeCommonHeader(
      h, IngestKind.pointCloud, IngestSourceFlags.points, payload);
  PointCloudPayload.setCount(h, 3);
  for (var i = 0; i < 3; i++) {
    PointCloudPayload.writePoint(
        h, i, 100 + i, i * 0.5, 1 + i * 0.25, -1.5, 0.25 * (i + 1));
  }
  return bytes;
}

/// Kind 3: two aggregated voxels.
Uint8List buildAggregatedVoxelMessage() {
  final payload = AggregatedVoxelPayload.payloadBytes(2);
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final h = _header..attach(ByteData.sublistView(bytes));
  _writeCommonHeader(h, IngestKind.aggregatedVoxels,
      IngestSourceFlags.depth | IngestSourceFlags.lidarClass, payload);
  AggregatedVoxelPayload.setCount(h, 2);
  AggregatedVoxelPayload.writeVoxel(
      h, 0, const [1.5, 3, -6], const [0, 0, 3], 3);
  AggregatedVoxelPayload.writeVoxel(
      h, 1, const [0.25, 0.5, 0.75], const [1, 0, 0], 1);
  return bytes;
}

final PhotoSavedView _photo = PhotoSavedView();

/// Kind 4: one photo saved event.
Uint8List buildPhotoSavedMessage() {
  const payload = PhotoSavedLayout.bytes;
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final data = ByteData.sublistView(bytes);
  final h = _header..attach(data);
  _writeCommonHeader(h, IngestKind.photoSaved, 0, payload);
  final p = _photo..attach(data, h.payloadOffset);
  p.writePhotoId(List<int>.generate(32, (i) => i));
  p.writeAttemptUuid(List<int>.generate(16, (i) => 0xa0 + i));
  p.exposureTimestampNs = 1234567890123;
  p.writeCameraToWorldRows(const [1, 0, 0, 0.1, 0, 1, 0, 0.2, 0, 0, 1, 0.3]);
  p.setIntrinsics(2800, 2801, 2000, 1500);
  p.widthDiv8 = 500;
  p.heightDiv8 = 375;
  p.sharpness = 812;
  p.flags = PhotoSavedLayout.flagAutomatic;
  p.groupEpochLow = fixtureGroupEpoch;
  return bytes;
}

final PhotoShotImageView _shotImage = PhotoShotImageView();

/// Kind 4 with the shot image extension (160 B payload): image 1 of a
/// burst of 3, manual, 4032 x 3024, its own pose and exposure time, tracking
/// normal. Not in [ingestGoldens], so the recording golden keeps its
/// messages; the Swift `ShotDurableCommitTests` write the same bytes.
Uint8List buildPhotoSavedShotImageMessage() {
  const payload = PhotoShotImageLayout.payloadBytes;
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final data = ByteData.sublistView(bytes);
  final h = _header..attach(data);
  _writeCommonHeader(h, IngestKind.photoSaved, 0, payload);
  final p = _photo..attach(data, h.payloadOffset);
  p.writePhotoId(List<int>.generate(32, (i) => 32 + i));
  p.writeAttemptUuid(List<int>.generate(16, (i) => 0xa0 + i));
  p.exposureTimestampNs = 1234567990123;
  p.writeCameraToWorldRows(const [1, 0, 0, 0.11, 0, 1, 0, 0.2, 0, 0, 1, 0.3]);
  p.setIntrinsics(2940, 2940, 2016, 1512);
  p.widthDiv8 = 504;
  p.heightDiv8 = 378;
  p.sharpness = 640;
  p.flags = 0;
  p.groupEpochLow = fixtureGroupEpoch;
  final s = _shotImage
    ..attach(data, h.payloadOffset + PhotoSavedLayout.bytes)
    ..begin();
  s.writeShotId(List<int>.generate(16, (i) => 0x10 + i));
  s.imageIndex = 1;
  s.imageCount = 3;
  s.shotMode = PhotoShotImageLayout.modeBurst;
  s.flags = 0;
  return bytes;
}

final MarkerDetectionRecordView _marker = MarkerDetectionRecordView();

/// Kind 5: two marker detections on a 640 x 480 grey image.
Uint8List buildMarkerDetectionsMessage() {
  final payload = MarkerDetectionsPayload.payloadBytes(2);
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final data = ByteData.sublistView(bytes);
  final h = _header..attach(data);
  _writeCommonHeader(h, IngestKind.markerDetections, 0, payload);
  h.width = 640;
  h.height = 480;
  MarkerDetectionsPayload.writePrefix(h, 2, 640, 480);
  for (var i = 0; i < 2; i++) {
    final m = _marker..attach(data, MarkerDetectionsPayload.recordOffset(h, i));
    m.markerId = 12 + i;
    m.dictionary = MarkerDictionary.arucoMip36h12;
    m.flags = i == 0 ? 0 : MarkerDetectionLayout.flagRejectedBlur;
    m.writeCorners([
      100.0 + i, 100, 140.0 + i, 100, 140.0 + i, 140, 100.0 + i, 140, //
    ]);
    m.setPose(0.1, -0.2, 0.3, 0.05 * i, 0, 1.5);
    m.reprojectionErrorPx = 0.75;
    m.viewingAngleDegrees = 30;
    m.sharpness = 200;
  }
  return bytes;
}

/// Kind 6: depth mode changed with value 42.
Uint8List buildSessionEventMessage() {
  const payload = SessionEventPayload.bytes;
  final bytes = Uint8List(IngestHeaderLayout.bytes + payload);
  final h = _header..attach(ByteData.sublistView(bytes));
  _writeCommonHeader(h, IngestKind.sessionEvent, 0, payload);
  SessionEventPayload.write(h, SessionEventKind.depthModeChanged, 42);
  return bytes;
}

/// Kind 0: idle.
Uint8List buildIdleMessage() {
  final bytes = Uint8List(IngestHeaderLayout.bytes);
  final h = _header..attach(ByteData.sublistView(bytes));
  h.begin(IngestKind.idle);
  h.sequence = fixtureSequence;
  h.sendTimeNs = fixtureSendTimeNs;
  h.poseMatch = PoseMatch.missing;
  return bytes;
}

/// Every ingest golden by file name.
final Map<String, Uint8List Function()> ingestGoldens = {
  'ingest_idle.bin': buildIdleMessage,
  'ingest_depth_u16.bin': buildDepthU16Message,
  'ingest_points.bin': buildPointCloudMessage,
  'ingest_voxels.bin': buildAggregatedVoxelMessage,
  'ingest_photo_saved.bin': buildPhotoSavedMessage,
  'ingest_markers.bin': buildMarkerDetectionsMessage,
  'ingest_session_event.bin': buildSessionEventMessage,
};
