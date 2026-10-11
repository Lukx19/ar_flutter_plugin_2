// Black-box acceptance of the platform boundary contract (plan 2.4, 2.7,
// 2.11): size limits, pose match, payload decoding into caller columns and
// recording replay, written from the published layouts only.
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/visibility_grid_contract/visibility_grid_contract.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List deltaMessage(int kind, int records) {
  final recordBytes = RendererDeltaKind.recordBytes(kind);
  final bytes =
      Uint8List(RendererDeltaLayout.headerBytes + records * recordBytes);
  final view = RendererDeltaView()..attach(ByteData.sublistView(bytes));
  view.begin(kind);
  view.recordCount = records;
  view.bufferCapacity = records;
  return bytes;
}

Uint8List pointMessage(List<List<double>> points) {
  final bytes = Uint8List(
      IngestHeaderLayout.bytes + PointCloudPayload.payloadBytes(points.length));
  final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
  view.begin(IngestKind.pointCloud);
  view.payloadBytes = PointCloudPayload.payloadBytes(points.length);
  view.poseMatch = PoseMatch.exact;
  PointCloudPayload.setCount(view, points.length);
  for (var i = 0; i < points.length; i++) {
    final p = points[i];
    PointCloudPayload.writePoint(view, i, i + 1, p[0], p[1], p[2], 0.9);
  }
  return bytes;
}

void main() {
  group('renderer delta size limit', () {
    test('a message of exactly 64 KiB is valid, one record more is not', () {
      final kind = RendererDeltaKind.freeSlots;
      final max = RendererDeltaLayout.maxRecords(kind);
      expect(RendererDeltaLayout.headerBytes + max * FreeSlotRangeLayout.bytes,
          RendererDeltaLayout.maxMessageBytes);
      final exact = deltaMessage(kind, max);
      expect(exact.length, 65536);
      final view = RendererDeltaView()..attach(ByteData.sublistView(exact));
      expect(view.validate(exact.length), ContractStatus.ok);
      expect(view.messageBytes, 65536);
      final over = deltaMessage(kind, max + 1);
      view.attach(ByteData.sublistView(over));
      expect(view.validate(over.length), isNot(ContractStatus.ok));
    });

    test('every record kind fits its largest record count in 64 KiB', () {
      for (final kind in [
        RendererDeltaKind.surfaceSlice,
        RendererDeltaKind.brickSummaries,
        RendererDeltaKind.occupancyMasks,
        RendererDeltaKind.regionSummaries,
        RendererDeltaKind.freeSlots,
        RendererDeltaKind.compactVoxels,
      ]) {
        final max = RendererDeltaLayout.maxRecords(kind);
        final bytes = RendererDeltaLayout.headerBytes +
            max * RendererDeltaKind.recordBytes(kind);
        expect(bytes, lessThanOrEqualTo(65536), reason: 'kind $kind');
        expect(bytes + RendererDeltaKind.recordBytes(kind), greaterThan(65536),
            reason: 'kind $kind');
      }
      expect(RendererDeltaKind.recordBytes(99), -1);
    });

    test('a message cut short is rejected', () {
      final bytes = deltaMessage(RendererDeltaKind.brickSummaries, 10);
      final view = RendererDeltaView()..attach(ByteData.sublistView(bytes));
      expect(view.validate(bytes.length - 1), isNot(ContractStatus.ok));
      expect(view.validate(10), isNot(ContractStatus.ok));
    });
  });

  group('ingest messages', () {
    test('a missing pose match carries no usable pose', () {
      final bytes = pointMessage([
        [0, 0, 1],
      ]);
      final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
      expect(view.hasMatchedPose, isTrue);
      for (final m in [
        PoseMatch.exact,
        PoseMatch.interpolated,
        PoseMatch.nearestWithin20Ms,
      ]) {
        view.poseMatch = m;
        expect(view.hasMatchedPose, isTrue, reason: 'match $m');
      }
      view.poseMatch = PoseMatch.missing;
      expect(view.hasMatchedPose, isFalse);
    });

    test('a message above 256 KB is rejected', () {
      final count =
          (IngestHeaderLayout.maxMessageBytes ~/ PointCloudPayload.pointBytes) +
              1;
      final bytes = Uint8List(
          IngestHeaderLayout.bytes + PointCloudPayload.payloadBytes(count));
      final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
      view.begin(IngestKind.pointCloud);
      view.payloadBytes = PointCloudPayload.payloadBytes(count);
      PointCloudPayload.setCount(view, count);
      expect(view.validate(bytes.length), isNot(ContractStatus.ok));
    });

    test('a declared payload longer than the bytes is rejected', () {
      final bytes = pointMessage([
        [0, 0, 1],
        [1, 0, 1],
      ]);
      final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
      expect(view.validate(bytes.length), ContractStatus.ok);
      expect(view.validate(bytes.length - 1), isNot(ContractStatus.ok));
    });

    test('an unknown kind is rejected', () {
      final bytes = pointMessage([
        [0, 0, 1],
      ]);
      final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
      view.kind = 9;
      expect(view.validate(bytes.length), isNot(ContractStatus.ok));
    });

    test('point columns decode up to the caller capacity', () {
      final bytes = pointMessage([
        for (var i = 0; i < 10; i++) [i * 1.0, 0, 1],
      ]);
      final view = IngestMessageView()..attach(ByteData.sublistView(bytes));
      final ids = Uint32List(4);
      final xyz = Float32List(12);
      final conf = Float32List(4);
      expect(PointCloudPayload.readColumns(view, ids, xyz, conf), 4);
      expect(ids, [1, 2, 3, 4]);
      expect(xyz[9], 3);
      expect(conf.every((c) => (c - 0.9).abs() < 1e-6), isTrue);
    });

    test('photo saved flags read back', () {
      final view = PhotoSavedView()..attach(ByteData(PhotoSavedLayout.bytes));
      view.flags = PhotoSavedLayout.flagBlurRejected;
      expect(view.blurRejected, isTrue);
      expect(view.automatic, isFalse);
      view.flags = PhotoSavedLayout.flagAutomatic;
      expect(view.automatic, isTrue);
      expect(view.blurRejected, isFalse);
    });
  });

  group('ingest recordings', () {
    Uint8List recording(List<Uint8List> messages, {int cut = 0}) {
      final total = IngestRecordingLayout.headerBytes +
          messages.fold<int>(0,
              (a, m) => a + IngestRecordingLayout.lengthPrefixBytes + m.length);
      final bytes = Uint8List(total);
      final data = ByteData.sublistView(bytes);
      (IngestRecordingHeaderView()..attach(data)).write(7, 8, messages.length);
      var o = IngestRecordingLayout.headerBytes;
      for (final m in messages) {
        data.setUint32(o, m.length, Endian.little);
        bytes.setRange(o + 4, o + 4 + m.length, m);
        o += 4 + m.length;
      }
      return Uint8List.sublistView(bytes, 0, total - cut);
    }

    test('a cursor replays every message in order and rewinds', () {
      final msgs = [
        for (var i = 1; i <= 3; i++)
          pointMessage([
            for (var k = 0; k < i; k++) [k * 1.0, 0, 1],
          ]),
      ];
      final rec = recording(msgs);
      final cursor = IngestRecordingCursor();
      expect(cursor.open(ByteData.sublistView(rec), rec.length),
          ContractStatus.ok);
      for (var round = 0; round < 2; round++) {
        final counts = <int>[];
        while (cursor.moveNext()) {
          counts.add(PointCloudPayload.count(cursor.message));
        }
        expect(counts, [1, 2, 3]);
        cursor.rewind();
      }
    });

    test('a truncated recording stops at the torn message with an error', () {
      final msgs = [
        pointMessage([
          [0, 0, 1],
        ]),
        pointMessage([
          [0, 0, 1],
          [1, 1, 1],
        ]),
      ];
      final rec = recording(msgs, cut: 5);
      final cursor = IngestRecordingCursor();
      expect(cursor.open(ByteData.sublistView(rec), rec.length),
          ContractStatus.ok);
      expect(cursor.moveNext(), isTrue);
      expect(cursor.moveNext(), isFalse);
      expect(cursor.lastStatus, isNot(ContractStatus.ok));
    });
  });

  group('status codes', () {
    test('codes are unique, stable and unknown codes map to null', () {
      final codes = NativeStatusCode.values.map((c) => c.code).toList();
      expect(codes.toSet(), hasLength(codes.length));
      expect(NativeStatusCode.fromCode(103), NativeStatusCode.depthStale);
      expect(NativeStatusCode.fromCode(102), NativeStatusCode.depthPoseMissing);
      expect(NativeStatusCode.fromCode(65000), isNull);
    });
  });
}
