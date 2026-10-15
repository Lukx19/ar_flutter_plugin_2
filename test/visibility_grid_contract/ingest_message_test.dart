import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

import 'contract_fixtures.dart';

void main() {
  group('ingest message goldens', () {
    for (final entry in ingestGoldens.entries) {
      test('${entry.key} encodes byte-exact', () {
        expectGoldenBytes(entry.key, entry.value());
      });
    }
  });

  test('header offsets match the normative table', () {
    expect(IngestHeaderLayout.bytes, 160);
    expect(IngestHeaderLayout.magic, 0x49563343); // 'C3VI'
    expect(IngestHeaderLayout.poseMatchOffset, 49);
    expect(IngestHeaderLayout.cameraToWorldOffset, 56);
    expect(IngestHeaderLayout.intrinsicsOffset, 120);
    expect(IngestHeaderLayout.droppedOffset, 144);
    expect(PhotoSavedLayout.bytes, 128);
    expect(PhotoSavedLayout.groupEpochLowOffset, 127);
    expect(MarkerDetectionLayout.bytes, 64);
    expect(MarkerDetectionLayout.reprojectionErrorOffset, 60);
    expect(PointCloudPayload.pointBytes, 20);
    expect(AggregatedVoxelPayload.voxelBytes, 28);
  });

  test('depth golden decodes header pose at the sample timestamp', () {
    final data = readGolden('ingest_depth_u16.bin');
    final h = IngestMessageView()..attach(data);
    expect(h.validate(data.lengthInBytes), ContractStatus.ok);
    expect(h.kind, IngestKind.depthImage);
    expect(h.sourceFlags, IngestSourceFlags.depth);
    expect(h.sequence, fixtureSequence);
    expect(h.sampleTimestampNs, fixtureSampleTimestampNs);
    expect(h.frameTimestampNs, fixtureFrameTimestampNs);
    expect(h.poseMatch, PoseMatch.interpolated);
    expect(h.hasMatchedPose, isTrue);
    expect(h.groupEpoch, fixtureGroupEpoch);
    expect(h.poseGapUs, fixturePoseGapUs);
    final pose = Float64List(16);
    h.readCameraToWorld(pose);
    expect(pose, orderedEquals(fixtureCameraToWorld));
    expect([h.fx, h.fy, h.cx, h.cy], [180, 181, 80, 45]);
    expect(h.dropped, fixtureDropped);
    expect(DepthImagePayload.payloadBytes(h), h.payloadBytes);
    expect(DepthImagePayload.depthMetres(h, 0, 0), 0.25);
    expect(DepthImagePayload.depthMetres(h, 3, 2), 3.0);
    expect(DepthImagePayload.confidence(h, 3, 2), 220);
  });

  test('point golden decodes into caller-owned feature point columns', () {
    final data = readGolden('ingest_points.bin');
    final h = IngestMessageView()..attach(data);
    expect(h.validate(data.lengthInBytes), ContractStatus.ok);
    final ids = Uint32List(8);
    final xyz = Float32List(24);
    final confidence = Float32List(8);
    expect(PointCloudPayload.readColumns(h, ids, xyz, confidence), 3);
    expect(ids.sublist(0, 3), [100, 101, 102]);
    expect(xyz.sublist(6, 9), [1.0, 1.5, -1.5]);
    expect(confidence.sublist(0, 3), [0.25, 0.5, 0.75]);
    // Columns smaller than the message are filled up to their capacity.
    expect(PointCloudPayload.readColumns(h, Uint32List(2), xyz, confidence), 2);
  });

  test('aggregated voxel golden decodes sums and counts', () {
    final data = readGolden('ingest_voxels.bin');
    final h = IngestMessageView()..attach(data);
    final positions = Float32List(6), normals = Float32List(6);
    final counts = Uint32List(2);
    expect(
        AggregatedVoxelPayload.readColumns(h, positions, normals, counts), 2);
    expect(positions, [1.5, 3, -6, 0.25, 0.5, 0.75]);
    expect(normals, [0, 0, 3, 1, 0, 0]);
    expect(counts, [3, 1]);
    expect(h.sourceFlags & IngestSourceFlags.lidarClass, isNonZero);
  });

  test('photo saved golden decodes every field', () {
    final data = readGolden('ingest_photo_saved.bin');
    final h = IngestMessageView()..attach(data);
    expect(h.validate(data.lengthInBytes), ContractStatus.ok);
    final p = PhotoSavedView()..attach(data, h.payloadOffset);
    final id = Uint8List(32);
    p.readPhotoId(id);
    expect(id, List<int>.generate(32, (i) => i));
    final uuid = Uint8List(16);
    p.readAttemptUuid(uuid);
    expect(uuid.first, 0xa0);
    expect(p.exposureTimestampNs, 1234567890123);
    expect(p.cameraToWorld(2, 3), closeTo(0.3, 1e-7));
    expect([p.fx, p.fy, p.cx, p.cy], [2800, 2801, 2000, 1500]);
    expect([p.widthDiv8, p.heightDiv8, p.sharpness], [500, 375, 812]);
    expect(p.automatic, isTrue);
    expect(p.blurRejected, isFalse);
    expect(p.groupEpochLow, fixtureGroupEpoch);
  });

  test('photo saved shot image golden decodes the event and its extension',
      () {
    expectGoldenBytes(
        'ingest_photo_saved_shot_image.bin', buildPhotoSavedShotImageMessage());
    final data = readGolden('ingest_photo_saved_shot_image.bin');
    final h = IngestMessageView()..attach(data);
    expect(h.validate(data.lengthInBytes), ContractStatus.ok);
    expect(h.payloadBytes, PhotoShotImageLayout.payloadBytes);
    final p = PhotoSavedView()..attach(data, h.payloadOffset);
    final id = Uint8List(32);
    p.readPhotoId(id);
    expect(id, List<int>.generate(32, (i) => 32 + i));
    expect(p.cameraToWorld(0, 3), closeTo(0.11, 1e-7));
    expect([p.fx, p.fy, p.cx, p.cy], [2940, 2940, 2016, 1512]);
    expect([p.widthDiv8, p.heightDiv8, p.sharpness], [504, 378, 640]);
    expect(p.automatic, isFalse);
    expect(p.hasCamera, isTrue);
    expect(
        PhotoShotImageView.present(data, h.payloadOffset, h.payloadBytes),
        isTrue);
    final s = PhotoShotImageView()
      ..attach(data, h.payloadOffset + PhotoSavedLayout.bytes);
    final shotId = Uint8List(16);
    s.readShotId(shotId);
    expect(shotId, List<int>.generate(16, (i) => 0x10 + i));
    expect([s.imageIndex, s.imageCount], [1, 3]);
    expect(s.shotMode, PhotoShotImageLayout.modeBurst);
    expect(s.trackingNotNormal, isFalse);
    expect(s.videoFrameFallback, isFalse);
    expect(p.exposureTimestampNs, 1234567990123);
  });

  test('a 128 B photo saved event has no shot image extension', () {
    final data = readGolden('ingest_photo_saved.bin');
    final h = IngestMessageView()..attach(data);
    expect(
        PhotoShotImageView.present(data, h.payloadOffset, h.payloadBytes),
        isFalse);
    // A 224 B payload with another magic is not an extension either.
    final shot = buildPhotoSavedShotImageMessage();
    final view = ByteData.sublistView(shot);
    final sh = IngestMessageView()..attach(view);
    view.setUint32(sh.payloadOffset + PhotoSavedLayout.bytes, 0);
    expect(PhotoShotImageView.present(view, sh.payloadOffset, sh.payloadBytes),
        isFalse);
  });

  test('marker golden decodes two 64 B records', () {
    final data = readGolden('ingest_markers.bin');
    final h = IngestMessageView()..attach(data);
    expect(MarkerDetectionsPayload.count(h), 2);
    expect(MarkerDetectionsPayload.imageWidth(h), 640);
    expect(MarkerDetectionsPayload.imageHeight(h), 480);
    final m = MarkerDetectionRecordView()
      ..attach(data, MarkerDetectionsPayload.recordOffset(h, 1));
    expect(m.markerId, 13);
    expect(m.flags, MarkerDetectionLayout.flagRejectedBlur);
    expect(m.corner(2), 141);
    expect(m.tvec(0), closeTo(0.05, 1e-7));
    expect(m.reprojectionErrorPx, 0.75);
    expect(m.viewingAngleDegrees, 30);
    expect(m.sharpness, 200);
  });

  test('session event and idle goldens decode', () {
    final event = IngestMessageView()
      ..attach(readGolden('ingest_session_event.bin'));
    expect(SessionEventPayload.event(event), SessionEventKind.depthModeChanged);
    expect(SessionEventPayload.value(event), 42);
    final idleData = readGolden('ingest_idle.bin');
    final idle = IngestMessageView()..attach(idleData);
    expect(idle.validate(idleData.lengthInBytes), ContractStatus.ok);
    expect(idle.kind, IngestKind.idle);
    expect(idle.payloadBytes, 0);
    expect(idle.hasMatchedPose, isFalse);
  });

  test('validation rejects magic, version, kind, truncation and size', () {
    final bytes = buildPointCloudMessage();
    final data = ByteData.sublistView(bytes);
    final h = IngestMessageView()..attach(data);
    expect(h.validate(bytes.length - 1), ContractStatus.badLength);
    expect(h.validate(100), ContractStatus.tooShort);
    data.setUint16(4, 2, Endian.little);
    expect(h.validate(bytes.length), ContractStatus.badVersion);
    data.setUint16(4, 1, Endian.little);
    h.kind = 9;
    expect(h.validate(bytes.length), ContractStatus.badKind);
    h.kind = IngestKind.pointCloud;
    h.payloadBytes = IngestHeaderLayout.maxMessageBytes;
    expect(h.validate(bytes.length), ContractStatus.tooLarge);
    data.setUint32(0, 0, Endian.little);
    expect(h.validate(bytes.length), ContractStatus.badMagic);
  });

  test('pull request round-trips in 8 B', () {
    final bytes = Uint8List(IngestPullRequestView.bytes);
    final view = IngestPullRequestView()..attach(ByteData.sublistView(bytes));
    view.write(0x7e);
    expectGoldenBytes('ingest_pull_request.bin', bytes);
    expect(view.validate(bytes.length), ContractStatus.ok);
    expect(view.acceptedKindMask, 0x7e);
  });

  test('pull request carries kind 8 in the high mask byte; an 8-bit mask '
      'reads as before', () {
    final bytes = Uint8List(IngestPullRequestView.bytes);
    final view = IngestPullRequestView()..attach(ByteData.sublistView(bytes));
    view.write(0x17e);
    expect(bytes[6], 0x7e);
    expect(bytes[7], 0x01);
    expect(view.acceptedKindMask & (1 << IngestKind.photoTiePoints), isNonZero);
    view.write(0x7e);
    expect(bytes[7], 0);
  });

  test('a photo tie point message (kind 8) validates', () {
    final bytes = Uint8List(IngestHeaderLayout.bytes + 64);
    final h = IngestMessageView()..attach(ByteData.sublistView(bytes));
    h
      ..begin(IngestKind.photoTiePoints)
      ..payloadBytes = 64;
    expect(h.validate(bytes.length), ContractStatus.ok);
    expect(h.kind, 8);
  });

  test('one view decodes many messages without reattaching buffers', () {
    final view = IngestMessageView();
    for (final build in ingestGoldens.values) {
      final bytes = build();
      view.attach(ByteData.sublistView(bytes));
      expect(view.validate(bytes.length), ContractStatus.ok);
    }
  });
}
